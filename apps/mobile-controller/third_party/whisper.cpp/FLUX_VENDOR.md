# whisper.cpp vendoring 说明

- 上游：https://github.com/ggml-org/whisper.cpp
- 版本：v1.9.6（tag）
- 源码包：https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.6.tar.gz
- 源码包 SHA-256：`7ab15dc88022d2425ad94dcffd8abbf1b6d7018089b4e48d6b40780738e0b445`
- 许可：MIT，见同目录 `LICENSE`（Copyright (c) 2023-2026 The ggml authors）

## 保留内容

只保留构建 `libfluxwhisper.so` 所需的核心源码与许可：

- `CMakeLists.txt`、`cmake/`、`include/`、`src/`、`ggml/`、`LICENSE`、`AUTHORS`、`README.md`
- `ggml/` 中只保留 CPU 后端（`ggml-cpu`）与核心源码；CUDA / Metal / Vulkan /
  OpenCL / SYCL / CANN 等手机端不用的后端目录已删除。
- `examples/`、`tests/`、`models/`、`samples/`、`bindings/`、`grammars/` 等
  不参与构建的目录未引入。

## 语音模型（不进本目录、不入库）

- 文件：`ggml-base-q5_1.bin`（OpenAI Whisper base 的官方量化版，MIT）
- 来源：<https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin>
- 大小：59,707,625 字节；SHA-256：`422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`
- 构建时由 `app/build.gradle.kts` 的 `fetchWhisperModel` 任务下载并校验，
  然后放进 APK 的 `assets/voice-models/`；运行时由 Kotlin 侧展开到应用私有目录。
- 权重缓存目录 `apps/mobile-controller/.whisper-models/` 与 assets 里的
  `*.bin` 都已 gitignore，禁止提交。
- 离线构建：把已校验的 `ggml-base-q5_1.bin` 放到
  `apps/mobile-controller/.whisper-models/`，任务会复用缓存、不再联网。

## 禁止事项

- 不要往本目录提交任何模型 `.bin` / `.gguf` 文件。
- 不要提交编译产物 `.so` / `.a` / `.o`。
- 升级版本时必须同步更新 `app/src/main/java/top/qiuli55/flux/mobile/ui/v2/V2LicenseScreen.kt`
  中的 `WHISPER_CPP_VERSION`、结果摘要与本文件的哈希。
