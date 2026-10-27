package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import top.qiuli55.flux.mobile.data.DesignPreview

// ------------------------------------------------------------------ 模型

data class V2Agent(
    val id: String,
    val name: String,
    val role: String,
    val load: Int,
    val running: Int,
    val tint: Color,
    /** 是否原生逐 token 流式；false 时界面标注「非逐字流式」。 */
    val nativeStreaming: Boolean = true,
)

/** 一个可选模型：keyId 非空表示来自密钥库。 */
data class V2ModelOption(
    val model: String,
    val label: String = model,
    val keyId: String? = null,
)

data class V2DeviceAgent(val agentId: String, val models: List<V2ModelOption>)

data class V2Device(
    val name: String,
    val kind: V2DeviceKind,
    val agents: List<V2DeviceAgent>,
)

enum class V2DeviceKind { LAPTOP, DESKTOP, PHONE }

/** 当前设备上已配置的 Agent + 该 Agent 可用模型。 */
data class V2AgentOption(val agent: V2Agent, val models: List<V2ModelOption>)

enum class V2MessageRole { SYSTEM, USER, AGENT }

/** 历史消息里的一条附件：只用来渲染文件名与大小。 */
data class V2MessageFile(val name: String, val size: Long)

/** 对话里的设计稿卡片：Agent 通过 design.share 写入，点开进入原生画布。 */
data class V2DesignCard(
    val id: String,
    val name: String,
    val project: String = "",
    val description: String = "",
    val version: Int = 0,
    val screenCount: Int = 0,
    val layerCount: Int = 0,
    val updatedAt: String = "",
    val bg: String = "#F4F6F9",
    /** 第一屏的尺寸与节点子集，用于卡片上的小画布预览。 */
    val preview: DesignPreview? = null,
)

/** 计划卡里的一步：标题必填，说明可空。 */
data class V2PlanStep(val title: String, val detail: String? = null)

/**
 * Agent 用 plan.write 产出的实施计划（kind = plan_card）。
 * 计划文档落在 Flux 自有目录，卡片只展示标题、步骤与涉及文件。
 */
data class V2PlanCard(
    val title: String,
    val steps: List<V2PlanStep> = emptyList(),
    val files: List<String> = emptyList(),
    val planPath: String = "",
    val markdown: String? = null,
)

/**
 * 审批卡（kind = decision / decision_result）。
 * kind 为 high_risk_command（正常模式高风险命令）或 protected_access（受保护对象，任何模式都问）。
 */
data class V2ApprovalCard(
    val id: String,
    val kind: String,
    val question: String,
    val detail: String = "",
    val command: String = "",
    val reason: String = "",
    /** pending / resolved / rejected / expired（auto_resolved 也按已决策显示）。 */
    val status: String = "pending",
    val chosen: String? = null,
    val expired: Boolean = false,
)

data class V2Message(
    val role: V2MessageRole,
    val text: String,
    val time: String,
    /** text = 普通消息；summary = 前情摘要；attachments = 附件消息；design_card = 设计稿卡片；
     *  plan_card = 实施计划；decision = 待审批；decision_result = 审批结果。 */
    val kind: String = "text",
    /** kind = attachments 时的文件清单。 */
    val attachments: List<V2MessageFile> = emptyList(),
    /** kind = design_card 时的设计稿信息。 */
    val designCard: V2DesignCard? = null,
    /** kind = plan_card 时的计划信息。 */
    val planCard: V2PlanCard? = null,
    /** kind = decision / decision_result 时的审批信息。 */
    val approvalCard: V2ApprovalCard? = null,
    /** run_finished 关联的 Run：有值时答案上方显示「任务耗时」入口。 */
    val runId: String = "",
    /** kind = handoff 时的交接双方（平台自动生成的 Agent 交接卡）。 */
    val handoffFrom: String = "",
    val handoffTo: String = "",
)

/**
 * 执行轨迹的一项：思考刻度或一次命令调用。
 *
 * 来自任务实时流的 `run_event`（kind = thinking / tool / error），
 * 只在本次会话里累积展示；对话历史仍以 run_finished 的最终答复为准。
 */
data class V2RunItem(
    val id: String,
    /** thinking / tool / error */
    val kind: String,
    val text: String = "",
    val name: String = "",
    val detail: String = "",
    val output: String = "",
    val status: String = "",
    /** command / read / write / other：实时累加概览计数用。 */
    val category: String = "",
    /** 该次工具调用触及的文件路径（服务端从入参提取）。 */
    val paths: List<String> = emptyList(),
    /** step_start：一轮模型调用的起点（没有推理文本时显示「思考中」） */
    val phase: String = "",
    /** 该项是不是「正在进行」（最近到达的那一项） */
    val live: Boolean = false,
)

data class V2Conversation(
    val id: String,
    val agentId: String,
    val title: String,
    val device: String,
    val source: String,
    val updated: String,
    val unread: Int,
    val messages: List<V2Message>,
)

enum class V2GroupStatus { RUNNING, BLOCKED, DONE }

enum class V2StageState { DONE, ACTIVE, PENDING }

data class V2Stage(val name: String, val note: String, val time: String, val state: V2StageState)

data class V2Event(val agentId: String, val text: String, val time: String)

data class V2Group(
    val id: String,
    val title: String,
    val device: String,
    val status: V2GroupStatus,
    val progress: Int,
    val stage: Int,
    val updated: String,
    val agents: List<String>,
    val stages: List<V2Stage>,
    val events: List<V2Event>,
)

enum class V2Risk { HIGH, MEDIUM, LOW }

data class V2Impact(val key: String, val value: String, val danger: Boolean = false)

data class V2Decision(
    val id: String,
    val risk: V2Risk,
    val age: String,
    val agentId: String,
    val title: String,
    val reason: String,
    val eta: String,
    val reversible: Boolean,
    val taskTitle: String,
    val impact: List<V2Impact>,
    val sql: String,
    val notes: List<String>,
    val taskId: String = "",
)

enum class V2TaskStatus { RUNNING, WAITING, DONE, FAILED, CANCELED }

data class V2Task(
    val id: String,
    val title: String,
    val agentId: String,
    val priority: String,
    val status: V2TaskStatus,
    val progress: Int,
    val note: String? = null,
)

enum class V2LogLevel { INFO, WARN, ERROR }

data class V2Log(val time: String, val level: V2LogLevel, val message: String)

// ------------------------------------------------------------------ 演示数据

val demoAgents = listOf(
    V2Agent("arch", "CodeArchitect", "云端架构重构", 42, 2, V2Colors.accent),
    V2Agent("ops", "DevOps-Pipeline", "CI/CD 自动化", 18, 1, V2Colors.violet),
    V2Agent("sec", "Security-Auditor", "安全漏洞扫描", 7, 0, Color(0xFF059669)),
)

private fun models(vararg names: String): List<V2ModelOption> = names.map { V2ModelOption(it, it) }

val demoDevices = listOf(
    V2Device(
        "MacBook Pro",
        V2DeviceKind.LAPTOP,
        listOf(
            V2DeviceAgent("arch", models("Claude Opus 4.5", "GPT-5.2", "DeepSeek-V3.2")),
            V2DeviceAgent("ops", models("Claude Sonnet 4.6", "GPT-5.1-Codex")),
            V2DeviceAgent("sec", models("Claude Sonnet 4.6", "DeepSeek-V3.2")),
        ),
    ),
    V2Device(
        "Studio",
        V2DeviceKind.DESKTOP,
        listOf(
            V2DeviceAgent("arch", models("Claude Opus 4.5", "Qwen3-Coder-480B")),
            V2DeviceAgent("ops", models("GPT-5.1-Codex")),
        ),
    ),
    V2Device(
        "iPhone",
        V2DeviceKind.PHONE,
        listOf(
            V2DeviceAgent("sec", models("DeepSeek-V3.2", "Claude Haiku 4.5")),
        ),
    ),
)

const val ALL_DEVICES = "全部设备"

/**
 * 运行时数据注册表。
 *
 * v2 页面历史上直接读 demo 数据，改为从 [V2Registry] 读；未登录 / 演示态时回落到 demo 内容，
 * 真实登录成功后由 V2AppViewModel 注入真实 Agent / 设备。
 */
object V2Registry {
    var agents: List<V2Agent> by mutableStateOf(demoAgents)
    var devices: List<V2Device> by mutableStateOf(demoDevices)
    var models: List<String> by mutableStateOf(emptyList())
    /** 后端每个 Agent 的可用模型（原生 + key 库）；空 = 回落到全局模型。 */
    var agentModels: Map<String, List<V2ModelOption>> by mutableStateOf(emptyMap())
}

private val unknownAgent = V2Agent("unknown", "Flux Agent", "未配置", 0, 0, V2Colors.accent)

/** 后端没有配置任何 Agent 时的兜底：任务仍然可以创建，由服务端选择默认 Agent。 */
private val fallbackAgent = V2Agent("", "Flux 默认 Agent", "尚未配置 Agent", 0, 0, V2Colors.accent)

fun demoAgent(id: String): V2Agent =
    V2Registry.agents.firstOrNull { it.id == id }
        ?: if (id.isBlank()) fallbackAgent else unknownAgent.copy(id = id, name = id.take(12))

private fun globalModelOptions(): List<V2ModelOption> = V2Registry.models.map { V2ModelOption(it, it) }

fun agentsForDevice(deviceName: String): List<V2AgentOption> {
    fun modelsOf(agentId: String, fallback: List<V2ModelOption>): List<V2ModelOption> =
        V2Registry.agentModels[agentId]?.takeIf { it.isNotEmpty() }
            ?: fallback.takeIf { it.isNotEmpty() }
            ?: globalModelOptions()

    val options = if (deviceName == ALL_DEVICES) {
        val merged = LinkedHashMap<String, MutableList<V2ModelOption>>()
        V2Registry.devices.forEach { device ->
            device.agents.forEach { configured ->
                val bucket = merged.getOrPut(configured.agentId) { mutableListOf() }
                configured.models.forEach { option ->
                    if (bucket.none { it.model == option.model && it.keyId == option.keyId }) {
                        bucket.add(option)
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            V2Registry.agents.map { V2AgentOption(it, modelsOf(it.id, globalModelOptions())) }
        } else {
            merged.map { (agentId, models) ->
                V2AgentOption(demoAgent(agentId), modelsOf(agentId, models.toList()))
            }
        }
    } else {
        val device = V2Registry.devices.firstOrNull { it.name == deviceName }
        device?.agents?.map { V2AgentOption(demoAgent(it.agentId), modelsOf(it.agentId, it.models)) }
            ?: V2Registry.agents.map { V2AgentOption(it, modelsOf(it.id, globalModelOptions())) }
    }
    return options.ifEmpty { listOf(V2AgentOption(fallbackAgent, globalModelOptions())) }
}

fun deviceKind(name: String): V2DeviceKind =
    V2Registry.devices.firstOrNull { it.name == name }?.kind ?: V2DeviceKind.LAPTOP

val demoConversations = listOf(
    V2Conversation(
        id = "c-4821",
        agentId = "arch",
        title = "生产库迁移方案确认",
        device = "MacBook Pro",
        source = "desktop",
        updated = "2 分钟前",
        unread = 2,
        messages = listOf(
            V2Message(V2MessageRole.SYSTEM, "Flux Cloud Kernel v2.1 · 会话已加密", "02:14:02"),
            V2Message(
                V2MessageRole.AGENT,
                "我在。可以让我检查集群、回滚部署，或者解释任何一条待决策的影响。",
                "02:14:06",
            ),
        ),
    ),
    V2Conversation(
        id = "c-4820",
        agentId = "ops",
        title = "多架构镜像构建失败",
        device = "MacBook Pro",
        source = "desktop",
        updated = "18 分钟前",
        unread = 0,
        messages = listOf(
            V2Message(V2MessageRole.SYSTEM, "Flux Cloud Kernel v2.1 · 会话已加密", "01:58:20"),
            V2Message(V2MessageRole.USER, "Docker 镜像多架构构建为什么失败了？", "01:58:31"),
            V2Message(
                V2MessageRole.AGENT,
                "arm64 基础镜像在拉取阶段超时，已自动重试 2 次；建议先预热节点缓存，再重跑一次构建。",
                "01:58:39",
            ),
        ),
    ),
    V2Conversation(
        id = "c-4818",
        agentId = "sec",
        title = "高权限令牌隔离确认",
        device = "iPhone",
        source = "mobile",
        updated = "1 小时前",
        unread = 0,
        messages = listOf(
            V2Message(V2MessageRole.SYSTEM, "本机会话 · 端到端加密", "00:52:10"),
            V2Message(V2MessageRole.USER, "3 个异常令牌先全部隔离，别影响在线业务。", "00:52:16"),
            V2Message(
                V2MessageRole.AGENT,
                "已隔离 3 个令牌，相关服务转只读运行；恢复写权限需要重新签发。",
                "00:52:24",
            ),
        ),
    ),
    V2Conversation(
        id = "c-4815",
        agentId = "arch",
        title = "扩容与副本数调整",
        device = "MacBook Pro",
        source = "desktop",
        updated = "昨天",
        unread = 0,
        messages = listOf(
            V2Message(V2MessageRole.SYSTEM, "Flux Cloud Kernel v2.1 · 会话已加密", "22:41:08"),
            V2Message(V2MessageRole.USER, "峰值前要把 api 副本扩到几个？", "22:41:15"),
            V2Message(
                V2MessageRole.AGENT,
                "6 提到 8 即可，数据库连接池仍有余量；峰值后自动回收 2 个副本。",
                "22:41:23",
            ),
        ),
    ),
    V2Conversation(
        id = "c-4809",
        agentId = "ops",
        title = "回滚脚本评审",
        device = "MacBook Pro",
        source = "desktop",
        updated = "10 月 20 日",
        unread = 0,
        messages = listOf(
            V2Message(V2MessageRole.SYSTEM, "Flux Cloud Kernel v2.1 · 会话已加密", "19:12:44"),
            V2Message(V2MessageRole.USER, "回滚脚本里要不要先锁表？", "19:12:52"),
            V2Message(
                V2MessageRole.AGENT,
                "不需要。脚本按批次回滚并校验主键，锁表会放大停顿；建议保留自动重试。",
                "19:13:01",
            ),
        ),
    ),
)

val demoGroups = listOf(
    V2Group(
        id = "g-201",
        title = "生产库迁移演练",
        device = "MacBook Pro",
        status = V2GroupStatus.RUNNING,
        progress = 62,
        stage = 1,
        updated = "3 分钟前",
        agents = listOf("arch", "ops", "sec"),
        stages = listOf(
            V2Stage("影响评估", "3 张表依赖梳理完成", "02:02", V2StageState.DONE),
            V2Stage("影子库回放", "正在回放 1.2M 行数据", "02:11", V2StageState.ACTIVE),
            V2Stage("安全复核", "等待 Security-Auditor 接入", "—", V2StageState.PENDING),
            V2Stage("等待批准", "需长按确认后执行", "—", V2StageState.PENDING),
        ),
        events = listOf(
            V2Event("ops", "影子库回放进度 62%，查询延迟稳定在 58ms", "02:18:24"),
            V2Event("arch", "索引方案已确认，回滚脚本已提交评审", "02:16:05"),
            V2Event("sec", "已加入复核队列，回放结束后检查锁等待", "02:14:41"),
        ),
    ),
    V2Group(
        id = "g-202",
        title = "web-console v2.4.0 灰度发布",
        device = "Studio",
        status = V2GroupStatus.RUNNING,
        progress = 40,
        stage = 1,
        updated = "9 分钟前",
        agents = listOf("arch", "ops"),
        stages = listOf(
            V2Stage("发布准备", "构建产物已签名", "01:48", V2StageState.DONE),
            V2Stage("5% 灰度", "internal-beta 组正在放量", "01:55", V2StageState.ACTIVE),
            V2Stage("指标观察", "错误率与 LCP 采样中", "—", V2StageState.PENDING),
            V2Stage("全量放量", "等待观察窗口结束", "—", V2StageState.PENDING),
        ),
        events = listOf(
            V2Event("ops", "灰度组 5% 放量完成，错误率 0.42%", "02:09:12"),
            V2Event("arch", "图表渲染改动的回归用例全部通过", "01:58:30"),
        ),
    ),
    V2Group(
        id = "g-196",
        title = "异常令牌隔离与审计",
        device = "MacBook Pro",
        status = V2GroupStatus.BLOCKED,
        progress = 75,
        stage = 2,
        updated = "26 分钟前",
        agents = listOf("sec", "ops"),
        stages = listOf(
            V2Stage("隔离令牌", "3 个令牌已阻断", "00:52", V2StageState.DONE),
            V2Stage("服务只读", "14 个服务已切换只读", "00:58", V2StageState.DONE),
            V2Stage("来源追溯", "等待安全值班授权", "—", V2StageState.ACTIVE),
            V2Stage("重新签发", "需业务方确认后执行", "—", V2StageState.PENDING),
        ),
        events = listOf(
            V2Event("sec", "境外 IP 归属已定位，等待调取审计日志的授权", "01:36:02"),
            V2Event("ops", "只读切换完成，写入队列已暂存", "01:12:44"),
        ),
    ),
    V2Group(
        id = "g-188",
        title = "CI 缓存清理与容量回收",
        device = "Studio",
        status = V2GroupStatus.DONE,
        progress = 100,
        stage = 3,
        updated = "昨天",
        agents = listOf("ops"),
        stages = listOf(
            V2Stage("容量盘点", "识别 42GB 可回收缓存", "21:04", V2StageState.DONE),
            V2Stage("清理构建缓存", "保留最近 30 天产物", "21:18", V2StageState.DONE),
            V2Stage("验证流水线", "冷启动构建耗时回到 6 分 20 秒", "21:41", V2StageState.DONE),
            V2Stage("容量回收", "磁盘占用降至 61%", "21:46", V2StageState.DONE),
        ),
        events = listOf(
            V2Event("ops", "清理完成，释放 38.6GB，流水线验证通过", "21:46:18"),
        ),
    ),
)

val demoDecisions = listOf(
    V2Decision(
        id = "d-1041",
        risk = V2Risk.HIGH,
        age = "2 分钟前",
        agentId = "arch",
        title = "执行生产库 schema 迁移",
        reason = "user_profiles 的 P95 查询已涨到 840ms，新增复合索引后预计降到 60ms；迁移放在低峰窗口，单表锁约 12 秒。预发库已完整回放两次，未出现锁等待堆积。",
        eta = "约 12 秒",
        reversible = true,
        taskTitle = "生产库 schema 迁移",
        impact = listOf(
            V2Impact("影响行数", "1.2M"),
            V2Impact("涉及表", "3"),
            V2Impact("可回滚", "是"),
        ),
        sql = "ALTER TABLE user_profiles\n  ADD INDEX idx_active_seen\n  (last_active_at, status);",
        notes = listOf("快照 snap-20261022-0210 已生成", "失败自动回滚已开启", "执行窗口 02:00–04:00"),
    ),
    V2Decision(
        id = "d-1042",
        risk = V2Risk.MEDIUM,
        age = "6 分钟前",
        agentId = "ops",
        title = "滚动重启 API 网关集群",
        reason = "v2.3.9 内存泄漏补丁已通过预发 48 小时压测；逐节点摘流重启，长连接不会中断。单节点摘流 30 秒，健康检查通过后再推进下一台。",
        eta = "约 90 秒",
        reversible = true,
        taskTitle = "网关集群滚动重启",
        impact = listOf(
            V2Impact("节点", "6"),
            V2Impact("数据变更", "0"),
            V2Impact("可回滚", "是"),
        ),
        sql = "kubectl rollout restart -n prod\n  deploy/api-gateway",
        notes = listOf("每节点摘流 30 秒", "健康检查通过后继续", "异常自动暂停 rollout"),
    ),
    V2Decision(
        id = "d-1043",
        risk = V2Risk.MEDIUM,
        age = "11 分钟前",
        agentId = "sec",
        title = "隔离 3 个异常高权限令牌",
        reason = "3 个令牌在非工作时间从境外 IP 调用管理接口，建议先隔离阻断，再排查来源与签发记录。安全值班已同步，隔离后可随时恢复。",
        eta = "约 5 秒",
        reversible = false,
        taskTitle = "隔离异常高权限令牌",
        impact = listOf(
            V2Impact("令牌", "3 个"),
            V2Impact("影响服务", "14"),
            V2Impact("可回滚", "否", danger = true),
        ),
        sql = "flux token revoke tk_9f2a tk_4c07 tk_b81d\n  --reason abnormal-ip --notify secops",
        notes = listOf("令牌清单已备份到安全库", "隔离后服务转只读运行", "需重新签发才能恢复写权限"),
    ),
    V2Decision(
        id = "d-1044",
        risk = V2Risk.LOW,
        age = "18 分钟前",
        agentId = "arch",
        title = "灰度发布 web-console v2.4.0",
        reason = "本次只改图表渲染与文案，预发回归全部通过；先放 5% 流量观察 30 分钟，异常自动回切。发布期间每 30 秒刷新一次灰度指标。",
        eta = "约 30 分钟",
        reversible = true,
        taskTitle = "灰度发布 web-console v2.4.0",
        impact = listOf(
            V2Impact("流量", "5%"),
            V2Impact("服务", "2"),
            V2Impact("可回滚", "是"),
        ),
        sql = "flux release promote web-console@2.4.0\n  --canary 5% --watch 30m",
        notes = listOf("灰度组：internal-beta", "观察指标：错误率、LCP", "异常自动回切 v2.3.9"),
    ),
)

val demoTasks = listOf(
    V2Task("t-301", "API 接口自动化回归测试", "ops", "P1", V2TaskStatus.RUNNING, 62),
    V2Task("t-302", "生成迁移回滚脚本", "arch", "P2", V2TaskStatus.WAITING, 0),
    V2Task(
        "t-303",
        "Docker 镜像多架构构建",
        "ops",
        "P1",
        V2TaskStatus.FAILED,
        0,
        note = "arm64 基础镜像拉取超时，已自动重试 2 次",
    ),
    V2Task("t-304", "静态代码安全扫描", "sec", "P2", V2TaskStatus.DONE, 100),
    V2Task("t-305", "清理 90 天前的构建缓存", "ops", "P3", V2TaskStatus.CANCELED, 0),
)

val demoInitialLogs = listOf(
    V2Log("--:--:--", V2LogLevel.INFO, "Flux Kernel v2.1 已连接 · 会话 #4821"),
    V2Log("--:--:--", V2LogLevel.INFO, "已同步 3 个 Agent 的运行状态"),
    V2Log("--:--:--", V2LogLevel.WARN, "检测到高危 SQL 操作，等待人工确认"),
    V2Log("--:--:--", V2LogLevel.INFO, "快照 snap-20261022-0210 生成完毕"),
)

val demoHeartbeats = listOf(
    V2LogLevel.INFO to "健康检查通过 · api-gateway-03 延迟 96ms",
    V2LogLevel.INFO to "索引重建进度 68%，预计还需 4 分钟",
    V2LogLevel.WARN to "web-console 灰度错误率 0.42%，接近阈值 0.5%",
    V2LogLevel.INFO to "任务队列写入 1 条新记录",
    V2LogLevel.INFO to "安全扫描完成，未发现新增高危漏洞",
    V2LogLevel.WARN to "限流触发 · 来源 10.0.4.18 命中 429 阈值",
    V2LogLevel.INFO to "自动快照保留策略生效，清理 2 份过期备份",
    V2LogLevel.INFO to "worker-02 心跳正常，负载 31%",
)

val demoChatSuggestions = listOf("集群现在怎么样？", "回滚最近一次部署", "查看异常日志", "给出扩容建议")

/** 与 demo 的回复规则逐字一致。 */
fun demoAgentReply(input: String): String = when {
    Regex("集群|状态|怎么样").containsMatchIn(input) ->
        "6 个节点全部在线，平均负载 38%，P95 延迟 112ms；近 15 分钟没有新的 ERROR。"
    Regex("回滚").containsMatchIn(input) ->
        "最近一次部署是 v2.3.9，回滚会影响 2 个服务、约 40 秒；需要你在队列里发起后长按确认。"
    Regex("日志|异常").containsMatchIn(input) ->
        "过去 15 分钟捕获 2 条 WARN、0 条 ERROR，摘要已推送到右上角日志抽屉。"
    Regex("扩容|建议").containsMatchIn(input) ->
        "峰值前把 api 副本从 6 提到 8 即可；数据库连接池仍有余量，不需要改配置。"
    else -> "收到「$input」。我正在解析意图并调度最近的 Agent 处理，结果会进入任务队列。"
}

fun groupStatusLabel(status: V2GroupStatus): String = when (status) {
    V2GroupStatus.RUNNING -> "协作中"
    V2GroupStatus.BLOCKED -> "等待授权"
    V2GroupStatus.DONE -> "已完成"
}

fun groupStatusColor(status: V2GroupStatus): Color = when (status) {
    V2GroupStatus.RUNNING -> V2Colors.cyan700
    V2GroupStatus.BLOCKED -> V2Colors.amber700
    V2GroupStatus.DONE -> V2Colors.emerald700
}
