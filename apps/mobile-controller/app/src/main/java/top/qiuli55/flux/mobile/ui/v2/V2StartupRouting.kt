package top.qiuli55.flux.mobile.ui.v2

import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.DeviceInfo

/**
 * 启动分流与设备自动选择（纯函数，可单测）。
 *
 * 原则：登录成功就进主界面。没有设备、设备列表为空、设备离线，都不能把整站
 * 挡在选设备页；设备选择只是「绑定电脑 Agent」的可选动作。
 */
internal fun resolveV2Boot(session: AccountSession?, config: ConnectionConfig): V2Boot = when {
    session != null -> V2Boot.READY
    config.isComplete -> V2Boot.READY
    else -> V2Boot.LOGIN
}

/**
 * 从未选过设备时自动挑一台：优先在线的 `platform=server` 设备，其次任意在线设备。
 * 一台都没有就返回 null——仍然进主界面，只是暂时没有电脑可执行。
 */
internal fun pickDeviceForBinding(devices: List<DeviceInfo>): DeviceInfo? =
    devices.firstOrNull { it.isOnline && it.platform == "server" }
        ?: devices.firstOrNull { it.isOnline }

/** 当前账号是否至少有一台在线的电脑 Agent（只用于给用户一句可读说明）。 */
internal fun hasOnlineComputer(devices: List<DeviceInfo>): Boolean =
    devices.any { it.isOnline }

/** 选中的设备离线时给用户的说明：允许进入，不拿离线挡路。 */
internal fun offlineDeviceNotice(device: DeviceInfo): String =
    "已选择「${device.name.ifBlank { "电脑" }}」，当前离线。" +
        "浏览和整理分享稿仍可用；需要电脑执行时会提示未开机。"
