// 顶层构建脚本：只声明插件版本，不在这里配置任何模块。
//
// 版本组合（快照日期 2026-10-05，联网核实官方兼容表 developer.android.com/build/releases/gradle-plugin）：
//   * AGP 8.7.2 要求 Gradle ≥ 8.9 → 用 8.9（本机已有该发行版缓存）
//   * Kotlin 2.0.21 自带 Compose 编译器插件（org.jetbrains.kotlin.plugin.compose），
//     不需要再手工指定 composeOptions.kotlinCompilerExtensionVersion
//   * Compose BOM 2024.02.01：把 compose-ui / material3 等版本交给 BOM 统一管理
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}