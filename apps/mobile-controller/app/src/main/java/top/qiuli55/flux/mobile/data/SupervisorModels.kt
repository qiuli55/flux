package top.qiuli55.flux.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------- 助理（Supervisor）

@Serializable
data class SupervisorSettings(
    @SerialName("accept_mode") val acceptMode: String = "observe",
    @SerialName("model_provider") val modelProvider: String = "",
    @SerialName("model_name") val modelName: String = "",
    @SerialName("max_tickets_per_thread") val maxTicketsPerThread: Int = 8,
    @SerialName("max_ticket_depth") val maxTicketDepth: Int = 3,
    @SerialName("max_concurrent_runs") val maxConcurrentRuns: Int = 2,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SupervisorEval(
    val id: String = "",
    @SerialName("trigger_id") val triggerId: String = "",
    val decision: String = "",
    val confidence: Double = 0.0,
    val reason: String = "",
    val risk: JsonObject = JsonObject(emptyMap()),
    val model: JsonObject = JsonObject(emptyMap()),
    @SerialName("latency_ms") val latencyMs: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class SupervisorTrigger(
    val id: String = "",
    @SerialName("source_key") val sourceKey: String = "",
    @SerialName("external_id") val externalId: String = "",
    @SerialName("event_type") val eventType: String = "",
    @SerialName("actor_id") val actorId: String = "",
    @SerialName("actor_platform") val actorPlatform: String = "",
    @SerialName("thread_key") val threadKey: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val content: String = "",
    val status: String = "",
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val eval: SupervisorEval? = null,
    val evals: List<SupervisorEval> = emptyList(),
)

@Serializable
data class SupervisorThread(
    val id: String = "",
    @SerialName("thread_key") val threadKey: String = "",
    @SerialName("source_key") val sourceKey: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val title: String = "",
    val summary: String = "",
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
)

@Serializable
data class SupervisorAssignment(
    val id: String = "",
    @SerialName("ticket_id") val ticketId: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val status: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class SupervisorReport(
    val id: String = "",
    @SerialName("ticket_id") val ticketId: String = "",
    @SerialName("assignment_id") val assignmentId: String? = null,
    val status: String = "",
    val summary: String = "",
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class SupervisorSubscription(
    val id: String = "",
    @SerialName("ticket_id") val ticketId: String = "",
    @SerialName("source_key") val sourceKey: String = "",
    @SerialName("external_id") val externalId: String = "",
    val mode: String = "",
)

@Serializable
data class SupervisorTicket(
    val id: String = "",
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("parent_ticket_id") val parentTicketId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("plan_key") val planKey: String = "",
    val title: String = "",
    val description: String = "",
    val role: String = "",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val degraded: Boolean = false,
    val sequence: Int = 0,
    val stage: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val assignment: SupervisorAssignment? = null,
    val report: SupervisorReport? = null,
    val assignments: List<SupervisorAssignment> = emptyList(),
    val reports: List<SupervisorReport> = emptyList(),
    val subscriptions: List<SupervisorSubscription> = emptyList(),
)

@Serializable
data class SupervisorPlanTicket(
    val key: String = "",
    val title: String = "",
    val role: String = "",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val sequence: Int = 0,
)

@Serializable
data class SupervisorPlan(
    val degraded: Boolean = false,
    val reason: String = "",
    @SerialName("latency_ms") val latencyMs: Int = 0,
    @SerialName("model_meta") val modelMeta: JsonObject = JsonObject(emptyMap()),
    val tickets: List<SupervisorPlanTicket> = emptyList(),
)

@Serializable
data class SupervisorDispatchOutcome(
    @SerialName("ticket_id") val ticketId: String = "",
    val dispatched: Boolean = false,
    val reason: String = "",
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
)

@Serializable
data class SupervisorPlanResult(
    val thread: SupervisorThread = SupervisorThread(),
    val plan: SupervisorPlan = SupervisorPlan(),
    val tickets: List<SupervisorTicket> = emptyList(),
    val dispatch: List<SupervisorDispatchOutcome> = emptyList(),
)

@Serializable
data class SupervisorOverview(
    val settings: SupervisorSettings = SupervisorSettings(),
    val counts: Map<String, Int> = emptyMap(),
    val tickets: List<SupervisorTicket> = emptyList(),
    @SerialName("pending_outbox") val pendingOutbox: Int = 0,
)

@Serializable
data class SupervisorTimelineItem(
    val at: String? = null,
    val kind: String = "",
    val id: String = "",
    val title: String = "",
    val status: String = "",
    @SerialName("source_key") val sourceKey: String? = null,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
    val summary: String? = null,
    @SerialName("ticket_id") val ticketId: String? = null,
)

@Serializable
data class SupervisorAssessment(
    val decision: String = "",
    val reason: String = "",
    val confidence: Double = 0.0,
    val risk: JsonObject = JsonObject(emptyMap()),
    @SerialName("should_dispatch") val shouldDispatch: Boolean = false,
    val title: String = "",
    @SerialName("model_meta") val modelMeta: JsonObject = JsonObject(emptyMap()),
    @SerialName("latency_ms") val latencyMs: Int = 0,
)

@Serializable
data class SupervisorAssessResult(
    val trigger: SupervisorTrigger = SupervisorTrigger(),
    val assessment: SupervisorAssessment? = null,
)

@Serializable
data class SupervisorWatchdogItem(
    @SerialName("ticket_id") val ticketId: String = "",
    val expired: Boolean = false,
    val reason: String = "",
)

@Serializable
data class SupervisorWatchdogResult(
    val checked: Int = 0,
    val expired: Int = 0,
    val items: List<SupervisorWatchdogItem> = emptyList(),
)

@Serializable
data class SupervisorProcessResult(
    val processed: Int = 0,
    @SerialName("pending_outbox") val pendingOutbox: Int = 0,
)

@Serializable
data class SupervisorStopResult(
    @SerialName("ticket_id") val ticketId: String = "",
    @SerialName("stopped_run") val stoppedRun: String? = null,
    val report: SupervisorReport? = null,
)

@Serializable
data class SupervisorDispatchThreadResult(
    val dispatched: Int = 0,
    val outcomes: List<SupervisorDispatchOutcome> = emptyList(),
)
