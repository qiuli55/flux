package top.qiuli55.flux.mobile.stt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 麦克风权限是否已授予。 */
fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** 打开本 App 的系统设置页，供用户手动补授权。 */
fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * AudioRecord 录音器：16kHz / 单声道 / 16-bit PCM，直接写成 WAV。
 *
 * 录音只落在应用私有 cacheDir，转写结束（成功 / 失败 / 取消 / 超时）后删除，
 * 不会同步到电脑或云端。
 */
internal class AndroidVoiceRecorder(
    private val context: Context,
) : VoiceRecorder {

    private var audioRecord: AudioRecord? = null
    private var writer: RandomAccessFile? = null
    private var worker: Thread? = null
    private var file: File? = null

    @Volatile
    private var recording = false

    @Volatile
    private var readFailed = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (recording) return true
        if (!hasRecordAudioPermission(context)) return false

        val target = File(context.cacheDir, "flux-voice-${System.currentTimeMillis()}.wav")
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (e: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return false
        }

        val raf = try {
            RandomAccessFile(target, "rw").apply {
                setLength(0)
                write(ByteArray(WAV_HEADER_SIZE))
            }
        } catch (e: Exception) {
            record.release()
            target.delete()
            return false
        }

        audioRecord = record
        writer = raf
        file = target
        readFailed = false
        recording = true

        try {
            record.startRecording()
        } catch (e: Exception) {
            recording = false
            runCatching { raf.close() }
            record.release()
            writer = null
            audioRecord = null
            target.delete()
            return false
        }

        worker = Thread({ recordLoop(record, raf) }, "flux-voice-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun recordLoop(record: AudioRecord, raf: RandomAccessFile) {
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms
        val bytes = ByteArray(buffer.size * 2)
        try {
            while (recording) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (count < 0) {
                        readFailed = true
                        break
                    }
                    // 0 不是错误，但别空转占满 CPU。
                    Thread.sleep(10L)
                    continue
                }
                var index = 0
                for (i in 0 until count) {
                    val value = buffer[i].toInt()
                    bytes[index++] = (value and 0xFF).toByte()
                    bytes[index++] = ((value shr 8) and 0xFF).toByte()
                }
                synchronized(raf) {
                    raf.write(bytes, 0, count * 2)
                }
            }
        } catch (e: Exception) {
            readFailed = true
        }
    }

    override fun stop(): File? {
        val raf = writer ?: return null
        val target = file
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { worker?.join(2_000L) }
        worker = null
        audioRecord?.release()
        audioRecord = null
        writer = null
        file = null

        var dataLength = 0L
        synchronized(raf) {
            dataLength = (raf.length() - WAV_HEADER_SIZE).coerceAtLeast(0L)
            if (dataLength > 0L) {
                raf.seek(0L)
                raf.write(wavHeader(dataLength))
            }
            runCatching { raf.close() }
        }
        if (target == null || readFailed || dataLength <= 0L) {
            target?.delete()
            return null
        }
        return target
    }

    override fun cancel() {
        val target = file
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { worker?.join(2_000L) }
        worker = null
        audioRecord?.release()
        audioRecord = null
        runCatching { writer?.close() }
        writer = null
        file = null
        target?.delete()
    }

    private fun wavHeader(dataLength: Long): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataLength).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2) // byte rate = rate * channels * bytesPerSample
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength.toInt())
        return header.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
    }
}
