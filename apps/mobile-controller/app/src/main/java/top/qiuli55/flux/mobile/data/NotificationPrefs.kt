package top.qiuli55.flux.mobile.data

/**
 * 本机提醒与偏好。
 *
 * 原来的这些开关只活在 Compose 的 remember 里，退出进程就丢；现在统一落到
 * [SettingsStore]（DataStore），进程被杀再打开仍然保留。
 *
 * 默认值与旧实现一致：决策提醒开、任务完成提醒关、触感开、减少动态关。
 */
data class NotificationPrefs(
    val decisionAlerts: Boolean = true,
    val taskAlerts: Boolean = false,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
) {
    /** 至少打开了一种系统提醒。 */
    val anyAlertEnabled: Boolean get() = decisionAlerts || taskAlerts
}
