// whisper.cpp JNI 薄封装：只暴露「给定模型 + 16kHz 单声道 16-bit WAV → 文本」。
//
// 说明：
//  - 模型随 APK 提供，由 Kotlin 侧从 assets 展开到应用私有目录并校验 SHA-256 后传进来；
//  - 语言由 Kotlin 侧固定传 "zh"；
//  - 推理全程在调用线程执行（Kotlin 侧放在后台线程），这里不碰 UI；
//  - 不使用 GPU / 网络，不依赖任何云端语音接口。

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "FluxWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kExpectedSampleRate = 16000;

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

// 读取本 App 自己录制的 WAV：RIFF/WAVE、PCM 16-bit、单声道、16kHz。
// 不做任意音频格式解码，避免把 ffmpeg 之类的大依赖带进安装包。
bool read_wav_16k_mono(const char * path, std::vector<float> & pcm, std::string & error) {
    FILE * file = fopen(path, "rb");
    if (file == nullptr) {
        error = "无法打开录音文件";
        return false;
    }

    char riff[4] = {0};
    char wave[4] = {0};
    uint32_t riff_size = 0;

    bool ok = false;
    bool fmt_ready = false;
    uint16_t audio_format = 0;
    uint16_t channels = 0;
    uint32_t sample_rate = 0;
    uint16_t bits_per_sample = 0;

    do {
        if (fread(riff, 1, 4, file) != 4 || fread(&riff_size, 4, 1, file) != 1 ||
            fread(wave, 1, 4, file) != 4) {
            error = "录音文件头不完整";
            break;
        }
        if (memcmp(riff, "RIFF", 4) != 0 || memcmp(wave, "WAVE", 4) != 0) {
            error = "录音文件不是 WAV";
            break;
        }

        while (true) {
            char chunk_id[4] = {0};
            uint32_t chunk_size = 0;
            if (fread(chunk_id, 1, 4, file) != 4 || fread(&chunk_size, 4, 1, file) != 1) {
                error = "录音文件缺少音频数据";
                break;
            }

            if (memcmp(chunk_id, "fmt ", 4) == 0) {
                unsigned char fmt[16] = {0};
                if (chunk_size < 16 || fread(fmt, 1, 16, file) != 16) {
                    error = "录音格式信息不完整";
                    break;
                }
                memcpy(&audio_format, fmt + 0, 2);
                memcpy(&channels, fmt + 2, 2);
                memcpy(&sample_rate, fmt + 4, 4);
                memcpy(&bits_per_sample, fmt + 14, 2);
                if (chunk_size > 16) {
                    fseek(file, static_cast<long>(chunk_size - 16 + (chunk_size % 2)), SEEK_CUR);
                }
                fmt_ready = true;
            } else if (memcmp(chunk_id, "data", 4) == 0) {
                if (!fmt_ready) {
                    error = "录音格式信息缺失";
                    break;
                }
                if (audio_format != 1 || channels != 1 || sample_rate != kExpectedSampleRate ||
                    bits_per_sample != 16) {
                    error = "录音格式不受支持";
                    break;
                }
                if (chunk_size == 0 || chunk_size % 2 != 0) {
                    error = "录音数据长度异常";
                    break;
                }
                std::vector<int16_t> raw(chunk_size / 2);
                if (fread(raw.data(), 1, chunk_size, file) != chunk_size) {
                    error = "录音数据读取不完整";
                    break;
                }
                pcm.resize(raw.size());
                constexpr float kScale = 1.0f / 32768.0f;
                for (size_t i = 0; i < raw.size(); ++i) {
                    pcm[i] = static_cast<float>(raw[i]) * kScale;
                }
                ok = true;
                break;
            } else {
                // 跳过未知 chunk（按 RIFF 规范补齐偶数字节）。
                fseek(file, static_cast<long>(chunk_size + (chunk_size % 2)), SEEK_CUR);
            }
        }
    } while (false);

    fclose(file);
    return ok;
}

std::string trim_ascii(const std::string & text) {
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && (text[begin] == ' ' || text[begin] == '\n' || text[begin] == '\r' ||
                           text[begin] == '\t')) {
        ++begin;
    }
    while (end > begin && (text[end - 1] == ' ' || text[end - 1] == '\n' || text[end - 1] == '\r' ||
                           text[end - 1] == '\t')) {
        --end;
    }
    return text.substr(begin, end - begin);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_top_qiuli55_flux_mobile_stt_WhisperBridge_nativeTranscribe(
    JNIEnv * env,
    jobject /* thiz */,
    jstring model_path,
    jstring wav_path,
    jstring language,
    jint threads) {
    if (model_path == nullptr || wav_path == nullptr || language == nullptr) {
        throw_illegal_state(env, "转写参数不完整");
        return nullptr;
    }

    const char * model_chars = env->GetStringUTFChars(model_path, nullptr);
    const char * wav_chars = env->GetStringUTFChars(wav_path, nullptr);
    const char * lang_chars = env->GetStringUTFChars(language, nullptr);
    if (model_chars == nullptr || wav_chars == nullptr || lang_chars == nullptr) {
        if (model_chars != nullptr) env->ReleaseStringUTFChars(model_path, model_chars);
        if (wav_chars != nullptr) env->ReleaseStringUTFChars(wav_path, wav_chars);
        if (lang_chars != nullptr) env->ReleaseStringUTFChars(language, lang_chars);
        throw_illegal_state(env, "转写参数读取失败");
        return nullptr;
    }

    std::string model_path_str(model_chars);
    std::string wav_path_str(wav_chars);
    std::string language_str(lang_chars);
    env->ReleaseStringUTFChars(model_path, model_chars);
    env->ReleaseStringUTFChars(wav_path, wav_chars);
    env->ReleaseStringUTFChars(language, lang_chars);

    std::vector<float> pcm;
    std::string read_error;
    if (!read_wav_16k_mono(wav_path_str.c_str(), pcm, read_error)) {
        LOGE("read wav failed: %s", read_error.c_str());
        throw_illegal_state(env, read_error.c_str());
        return nullptr;
    }
    if (pcm.empty()) {
        throw_illegal_state(env, "录音是空的");
        return nullptr;
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    whisper_context * context =
        whisper_init_from_file_with_params(model_path_str.c_str(), context_params);
    if (context == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        throw_illegal_state(env, "语音模型加载失败");
        return nullptr;
    }

    whisper_full_params params =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.language = language_str.c_str();
    params.detect_language = false;

    int thread_count = static_cast<int>(threads);
    if (thread_count < 1) thread_count = 1;
    if (thread_count > 4) thread_count = 4;  // 手机上限制线程数，避免过度耗电/发热
    params.n_threads = thread_count;

    const int result = whisper_full(context, params, pcm.data(), static_cast<int>(pcm.size()));
    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
        whisper_free(context);
        throw_illegal_state(env, "本地转写失败");
        return nullptr;
    }

    std::string output;
    const int segment_count = whisper_full_n_segments(context);
    for (int i = 0; i < segment_count; ++i) {
        const char * segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) {
            output.append(segment);
        }
    }
    whisper_free(context);

    output = trim_ascii(output);
    return env->NewStringUTF(output.c_str());
}
