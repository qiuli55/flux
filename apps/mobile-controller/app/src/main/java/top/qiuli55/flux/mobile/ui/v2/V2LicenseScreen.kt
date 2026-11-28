package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.stt.VOICE_MODEL_FILE_NAME
import top.qiuli55.flux.mobile.stt.VOICE_MODEL_SHA256
import top.qiuli55.flux.mobile.stt.VOICE_MODEL_SIZE_BYTES
import top.qiuli55.flux.mobile.stt.VOICE_MODEL_SOURCE_REPO
import java.util.Locale

/**
 * 「我的」→「关于 / 开源许可」。
 *
 * 只在这里集中展示实现细节（whisper.cpp 版本、模型文件、MIT 声明与来源），
 * 录音 / 转写界面不出现模型文件名或引擎名，避免打扰普通用户。
 */
const val OPEN_SOURCE_SCREEN_TITLE: String = "关于 / 开源许可"
const val LICENSE_SCREEN_TAG: String = "v2_license_screen"

/** 与 third_party/whisper.cpp 的 vendoring 版本保持一致。 */
const val WHISPER_CPP_VERSION: String = "v1.9.6"
const val WHISPER_CPP_COPYRIGHT: String = "Copyright (c) 2023-2026 The ggml authors"
const val OPENAI_WHISPER_COPYRIGHT: String = "Copyright (c) 2022 OpenAI"

/** 供许可页展示的 MIT 全文；引擎与模型各自保留同一份许可声明。 */
const val MIT_LICENSE_TEXT: String = """MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

@Composable
fun V2LicenseScreen(
    version: String,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .testTag(LICENSE_SCREEN_TAG)
            .verticalScroll(rememberScrollState())
            .padding(top = extraTop, bottom = 28.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(
                    Icons.Outlined.ArrowBack,
                    onBack,
                    size = 34.dp,
                    iconSize = 16.dp,
                    contentDescription = "返回",
                )
                Spacer(Modifier.width(10.dp))
            }
            Label(
                OPEN_SOURCE_SCREEN_TITLE,
                22f,
                V2Colors.ink(.95f),
                FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Label(
            "Flux 手机端 $version · 语音转文字在本机完成",
            12f,
            V2Colors.ink(.42f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(6.dp))
        WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Label("语音转文字", 15f, V2Colors.ink(.92f), FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Label(
                    "录音只保存在本机临时目录，用手机上的离线模型转成文字；" +
                        "音频不会上传到 Flux 后端、用户电脑或第三方转写服务。" +
                        "转写结果只进入输入框，由用户自己决定是否发送。",
                    11f,
                    V2Colors.ink(.55f),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Label("whisper.cpp", 15f, V2Colors.ink(.92f), FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LicenseLine("版本", WHISPER_CPP_VERSION)
                LicenseLine("许可", "MIT")
                LicenseLine("版权", WHISPER_CPP_COPYRIGHT)
                LicenseLine("来源", "https://github.com/ggml-org/whisper.cpp")
                Spacer(Modifier.height(8.dp))
                Label(MIT_LICENSE_TEXT, 9.5f, V2Colors.ink(.48f), mono = true)
            }
        }

        Spacer(Modifier.height(6.dp))
        WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Label("OpenAI Whisper 官方模型", 15f, V2Colors.ink(.92f), FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LicenseLine("模型", "OpenAI Whisper base（$VOICE_MODEL_FILE_NAME）")
                LicenseLine("提供方式", "随安装包内置，安装后即可离线转写")
                LicenseLine("许可", "MIT")
                LicenseLine("版权", OPENAI_WHISPER_COPYRIGHT)
                LicenseLine("模型卡", "https://huggingface.co/openai/whisper-base")
                LicenseLine(
                    "来源",
                    "$VOICE_MODEL_SOURCE_REPO（官方转换 / 量化权重）",
                )
                LicenseLine("SHA-256", VOICE_MODEL_SHA256)
                LicenseLine(
                    "大小",
                    "${String.format(Locale.ROOT, "%,d", VOICE_MODEL_SIZE_BYTES)} 字节（约 57 MiB）",
                )
                Spacer(Modifier.height(8.dp))
                Label(MIT_LICENSE_TEXT, 9.5f, V2Colors.ink(.48f), mono = true)
            }
        }

        Spacer(Modifier.height(6.dp))
        Label(
            "语音模型随安装包一起提供，首次使用无需联网下载；安装后即可在本机离线转写。",
            10.5f,
            V2Colors.ink(.40f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LicenseLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Label(label, 10.5f, V2Colors.ink(.42f), modifier = Modifier.width(52.dp))
        Label(value, 10.5f, V2Colors.ink(.62f), modifier = Modifier.weight(1f), mono = true)
    }
}
