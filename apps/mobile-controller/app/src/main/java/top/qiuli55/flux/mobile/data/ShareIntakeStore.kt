package top.qiuli55.flux.mobile.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 磁盘上一条待消费的系统分享。
 *
 * 登录墙 / 选设备期间进程被杀是常态：文字、错误和已经拷到 `shared-inbox` 的 file Uri
 * 必须先落盘，回到主界面后再投递到 [ShareIntakeBus]。
 */
@Serializable
data class PersistedSharedIntake(
    val id: String,
    val text: String = "",
    val errors: List<String> = emptyList(),
    val attachments: List<PersistedSharedAttachment> = emptyList(),
)

@Serializable
data class PersistedSharedAttachment(
    val uri: String,
    val name: String = "",
)

/**
 * 极小的分享清单文件：只保存「还没真正应用到输入栏」的分享。
 *
 * 用普通文件而不是 DataStore：读写都是同步的、可注入目录，Robolectric 里能直接单测，
 * 也不需要为一次分享承担 Flow / 协程初始化成本。写入走临时文件 + rename，避免半截 JSON。
 */
class SharedIntakeStore(private val file: File) {
    private val lock = Any()

    fun load(): List<PersistedSharedIntake> = synchronized(lock) { readLocked() }

    fun add(record: PersistedSharedIntake): List<PersistedSharedIntake> = synchronized(lock) {
        val next = readLocked().filterNot { it.id == record.id } + record
        writeLocked(next)
        next
    }

    fun remove(id: String): List<PersistedSharedIntake> = synchronized(lock) {
        val current = readLocked()
        val next = current.filterNot { it.id == id }
        if (next.size != current.size) writeLocked(next)
        next
    }

    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        Unit
    }

    private fun readLocked(): List<PersistedSharedIntake> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<PersistedSharedIntake>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(records: List<PersistedSharedIntake>) {
        runCatching {
            val directory = file.parentFile
            directory?.mkdirs()
            val jsonText = json.encodeToString(records)
            if (directory == null) {
                file.writeText(jsonText)
            } else {
                val tmp = File(directory, file.name + ".tmp")
                tmp.writeText(jsonText)
                if (!tmp.renameTo(file)) {
                    // 某些文件系统不支持覆盖式 rename：退回到直接写，别把分享弄丢。
                    file.writeText(jsonText)
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        private const val DIRECTORY = "share-intake"
        private const val FILE_NAME = "pending.json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun inDirectory(directory: File): SharedIntakeStore =
            SharedIntakeStore(File(directory, FILE_NAME))

        fun forContext(context: Context): SharedIntakeStore =
            inDirectory(File(context.filesDir, DIRECTORY))
    }
}

/**
 * 进程内只保留一个 store 实例，MainActivity 写入与页面消费后的删除共用同一把锁。
 */
object SharedIntakeStorage {
    @Volatile
    private var cached: SharedIntakeStore? = null

    fun of(context: Context): SharedIntakeStore {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: SharedIntakeStore.forContext(context.applicationContext).also { cached = it }
        }
    }

    internal fun resetForTest() {
        synchronized(this) { cached = null }
    }
}

internal fun IncomingShare.toPersistedRecord(
    id: String = UUID.randomUUID().toString(),
): PersistedSharedIntake = PersistedSharedIntake(
    id = id,
    text = text,
    errors = errors,
    attachments = attachments.map { PersistedSharedAttachment(it.uri.toString(), it.name) },
)

internal fun PersistedSharedIntake.toIncomingShare(): IncomingShare = IncomingShare(
    text = text,
    attachments = attachments.map { SharedAttachmentRef(Uri.parse(it.uri), it.name) },
    errors = errors,
    recordId = id,
)

/**
 * 分享的落盘 / 恢复 / 消费确认。
 *
 * 「恢复」只负责把磁盘上未消费的记录重新投递到总线；真正的删除由页面在内容
 * 进入输入栏后调用 [acknowledge]。这样登录墙 / 选设备期间进程被杀也不会丢分享。
 */
object ShareIntakeRecovery {
    /**
     * 把磁盘上还没消费的记录投递出去。
     *
     * [alreadyPending] 默认取当前总线里已有的 recordId：同一条记录已经排队时不再重复投递，
     * 避免 Activity 重建 / onCreate 再次执行时同一条分享被应用两次。
     */
    fun restore(
        store: SharedIntakeStore,
        alreadyPending: Set<String> = ShareIntakeBus.pendingRecordIds(),
        deliver: (IncomingShare) -> Unit = ShareIntakeBus::publish,
    ): Int {
        var delivered = 0
        store.load().forEach { record ->
            if (record.id in alreadyPending) return@forEach
            deliver(record.toIncomingShare())
            delivered++
        }
        return delivered
    }

    /**
     * 先把一份新分享写入磁盘，再返回带上 [IncomingShare.recordId] 的副本。
     *
     * 调用方负责把返回值投递到总线；如果它已经是恢复出来的记录（recordId 非空），原样返回。
     */
    fun persist(store: SharedIntakeStore, share: IncomingShare): IncomingShare {
        if (share.recordId != null) return share
        val record = share.toPersistedRecord()
        store.add(record)
        return share.copy(recordId = record.id)
    }

    /** 内容已经按落地规则应用到输入栏（或已提示拒绝原因）后才删除磁盘记录。 */
    fun acknowledge(store: SharedIntakeStore, recordId: String?) {
        if (recordId == null) return
        store.remove(recordId)
    }
}
