# 语音转文字：本机 whisper.cpp 推理库（JNI）。
#
# whisper.cpp 源码以 vendoring 方式放在 apps/mobile-controller/third_party/whisper.cpp，
# 版本 v1.9.6（MIT，见该目录 LICENSE）。这里只编 CPU 后端，不启用任何 GPU / 加速后端。
# 模型权重不在此编译：由 Gradle 的 fetchWhisperModel 任务打进 APK assets，
# 运行时由 Kotlin 侧展开到应用私有目录后把路径传进来。

cmake_minimum_required(VERSION 3.22)
project(fluxwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只保留手机端实际用得到的构建选项，避免引入无关依赖与体积。
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
set(WHISPER_VITISAI OFF CACHE BOOL "" FORCE)

# 静态库直接链进 libfluxwhisper.so，安装包里只多一个 native 库。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp
)

add_library(fluxwhisper SHARED whisper_jni.cpp)

target_link_libraries(fluxwhisper PRIVATE whisper)
find_library(ANDROID_LOG_LIB log)
if (ANDROID_LOG_LIB)
    target_link_libraries(fluxwhisper PRIVATE ${ANDROID_LOG_LIB})
endif()
