package app.resound.agent

import app.resound.AppGraph
import app.resound.auth.Capability
import app.resound.data.CaptureDatabase
import app.resound.digest.SecretStore
import app.resound.diagnostics.FailureNotes
import kotlinx.coroutines.CoroutineDispatcher
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Agent 桥接：端点管理（密钥走系统安全区）、任务生命周期 + 幂等发送 + 回执 + 失败重试。
 * 对外只发用户在界面确认过的文本载荷；绝不静默上传音频或整库记录。
 * 未配置端点时：任务可创建、可管理、可导出 JSON，不能显示“已发送 / 执行成功”。
 */
class AgentBridge(
    context: android.content.Context,
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val io: CoroutineDispatcher,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val secrets = SecretStore(context, "agent-secrets", "agent-bridge-v1")
    private val dao get() = db.agent()
    private val sendLock = Mutex()

    val endpoints: Flow<List<AgentEndpointRow>> get() = dao.observeEndpoints()
    val tasks: Flow<List<AgentTaskRow>> get() = dao.observeTasks()
    val openTasks: Flow<List<AgentTaskRow>> get() = dao.observeOpen()
    val hasEndpoint: Flow<Boolean> = endpoints.map { it.isNotEmpty() }
    fun observeTask(id: Long) = dao.observeTask(id)

    // ---------- 端点 ----------

    /** 保存端点；secret 为 null 表示不改密钥，为空串表示清除。返回端点 id。 */
    suspend fun saveEndpoint(id: Long?, name: String, url: String, headersJson: String = "{}",
        secret: String? = null, statusUrlTemplate: String = "", enabled: Boolean = true): Result<Long> =
        withContext(io) {
            runCatching {
                require(name.isNotBlank()) { "端点名不能为空" }
                val (ok, reason) = AgentRules.endpointUrlValid(url)
                require(ok) { reason }
                val endpointId = dao.transaction {
                    if (id == null) {
                        dao.insertEndpoint(AgentEndpointRow(name = name.trim(), url = url.trim(),
                            headersJson = headersJson, secretName = "", statusUrlTemplate = statusUrlTemplate.trim(),
                            enabled = enabled, createdAt = now.now(), updatedAt = now.now()))
                    } else {
                        val old = dao.endpoint(id) ?: error("端点不存在")
                        dao.updateEndpoint(old.copy(name = name.trim(), url = url.trim(), headersJson = headersJson,
                            statusUrlTemplate = statusUrlTemplate.trim(), enabled = enabled, updatedAt = now.now()))
                        id
                    }
                }
                // 密钥随端点 id 键入安全区；旧键名不同就换绑并清掉
                val row = dao.endpoint(endpointId)!!
                val expected = "endpoint-$endpointId"
                if (row.secretName != expected) {
                    if (row.secretName.isNotBlank()) secrets.remove(row.secretName)
                    dao.updateEndpoint(row.copy(secretName = expected))
                }
                when {
                    secret == null -> Unit // 不动
                    secret.isBlank() -> secrets.remove(expected)
                    else -> secrets.write(expected, secret.trim())
                }
                endpointId
            }
        }

    suspend fun deleteEndpoint(id: Long) = withContext(io) {
        dao.endpoint(id)?.secretName?.takeIf { it.isNotBlank() }?.let { secrets.remove(it) }
        dao.deleteEndpoint(id)
    }

    fun endpointSecret(row: AgentEndpointRow): String =
        if (row.secretName.isBlank()) "" else runCatching { secrets.read(row.secretName) }.getOrDefault("")

    // ---------- 任务 ----------

    /** 创建任务（Pending 待确认）。content 是用户已在界面看到的正文；返回本地行 id。 */
    suspend fun createTask(kind: AgentTaskKind, title: String, content: String, context: String = "",
        endpointId: Long? = null, sourceKind: String = "Manual", sourceId: Long? = null): Long = withContext(io) {
        val taskId = AgentRules.newTaskId(now.now())
        val payload = AgentTaskPayload(taskId = taskId, type = kind.wire, title = title.trim(),
            content = content, context = context, sourceKind = sourceKind, sourceId = sourceId,
            createdAt = now.now(), callbackHint = "resound://agent/task/$taskId")
        dao.insertTask(AgentTaskRow(taskId = taskId, endpointId = endpointId, type = kind.name,
            title = title.trim(), payloadJson = AgentRules.payloadJson(payload),
            sourceKind = sourceKind, sourceId = sourceId, createdAt = now.now(), updatedAt = now.now()))
    }

    /** 用户确认发送内容：Pending → Confirmed，可立即尝试一次发送。 */
    suspend fun confirm(id: Long, dispatch: Boolean = true): Boolean = withContext(io) {
        val task = dao.task(id) ?: return@withContext false
        if (AgentTaskStatus.of(task.status) != AgentTaskStatus.Pending) return@withContext false
        dao.setStatus(id, AgentTaskStatus.Confirmed.name, now.now()) > 0
    }.also { ok -> if (ok && dispatch) kick(id) }

    /** 撤回确认 / 失败重发 / 取消后重建：回到可发送队列。 */
    suspend fun requeue(id: Long): Boolean = withContext(io) {
        val task = dao.task(id) ?: return@withContext false
        val from = AgentTaskStatus.of(task.status)
        if (!AgentTaskStatus.canMove(from, AgentTaskStatus.Confirmed)) return@withContext false
        dao.setStatus(id, AgentTaskStatus.Confirmed.name, now.now()) > 0
    }.also { ok -> if (ok) kick(id) }

    suspend fun cancel(id: Long): Boolean = withContext(io) {
        val task = dao.task(id) ?: return@withContext false
        val from = AgentTaskStatus.of(task.status)
        if (!AgentTaskStatus.canMove(from, AgentTaskStatus.Cancelled)) return@withContext false
        dao.setStatus(id, AgentTaskStatus.Cancelled.name, now.now()) > 0
    }

    /** 无回查能力的端点：用户在界面核实过外部结果后手动收尾。不用于冒充成功。 */
    suspend fun resolveManually(id: Long, succeeded: Boolean, note: String = ""): Boolean = withContext(io) {
        val task = dao.task(id) ?: return@withContext false
        if (AgentTaskStatus.of(task.status) != AgentTaskStatus.Running) return@withContext false
        (dao.finishRunning(id, if (succeeded) AgentTaskStatus.Succeeded.name else AgentTaskStatus.Failed.name,
            note, if (succeeded) null else note.ifBlank { "人工标记失败" }, now.now()) > 0)
            .also { if (it && succeeded) writeBackSource(task) }
    }

    /** 任务拿到真实结果后的源对象回写：灵感从"待研究"推进到"研究中"（外部有回执≠已验证）。 */
    private suspend fun writeBackSource(task: AgentTaskRow) {
        if (!task.sourceKind.equals("idea", ignoreCase = true)) return
        val srcId = task.sourceId ?: return
        runCatching {
            val ideaDao = db.ideas()
            val idea = ideaDao.idea(srcId) ?: return@runCatching
            if (idea.status == app.resound.inbox.IdeaStatus.Research.name)
                ideaDao.setStatus(idea.id, app.resound.inbox.IdeaStatus.Studying.name, now.now())
        }
    }

    suspend fun deleteTask(id: Long) = withContext(io) {
        val task = dao.task(id) ?: return@withContext
        if (AgentTaskStatus.of(task.status) == AgentTaskStatus.Running) return@withContext
        dao.deleteTask(id)
    }

    suspend fun payloadOf(id: Long): String? = withContext(io) { dao.task(id)?.payloadJson }

    /** 外部回执：把服务端最新状态写回（供轮询或手动刷新）。 */
    suspend fun refresh(id: Long): Boolean = withContext(io) {
        val task = dao.task(id) ?: return@withContext false
        if (AgentTaskStatus.of(task.status) != AgentTaskStatus.Running) return@withContext false
        val endpoint = task.endpointId?.let { dao.endpoint(it) } ?: return@withContext false
        if (endpoint.statusUrlTemplate.isBlank()) return@withContext false
        poll(task, endpoint)
    }

    // ---------- 发送 ----------

    /** 立即在后台尝试发送指定任务（CAS 保证不会重发）。 */
    fun kick(id: Long) { scope.launch { runCatching { send(id) }.onFailure { FailureNotes.record("agent/AgentBridge.kt:kick", it) } } }

    /** 把所有到期的任务发一轮（启动时与 WorkManager 周期调用）。 */
    suspend fun dispatchDue(): Int = withContext(io) {
        val due = dao.dispatchable(now.now())
        var sent = 0
        for (task in due) if (send(task.id)) sent++
        sent
    }

    /** 发送单个任务：CAS 认领 → POST → 记回执。成功受理进入 Running；失败按退避排重试。 */
    suspend fun send(id: Long): Boolean = sendLock.withLock {
        withContext(io) {
            val at = now.now()
            val task = dao.task(id) ?: return@withContext false
            // AUTH：未授权 Agent 访问不出网；任务挂起待授权后重试，不算失败。
            if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.AgentAccess)) {
                dao.scheduleRetry(id, "账号未授权 Agent 访问", null, at)
                return@withContext false
            }
            if (dao.claimForSend(id, at) == 0) return@withContext false
            val endpoint = task.endpointId?.let { dao.endpoint(it) }
            if (endpoint == null || !endpoint.enabled) {
                dao.scheduleRetry(id, "未配置可用端点", null, now.now())
                return@withContext false
            }
            try {
                val request = buildRequest(task, endpoint)
                val started = now.now()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string().orEmpty().take(4000)
                    val (remoteId, remoteStatus) = AgentRules.remoteAck(body)
                    if (code in 200..299) {
                        val receipt = AgentReceipt(httpStatus = code, elapsedMs = now.now() - started,
                            remoteId = remoteId, remoteStatus = remoteStatus,
                            note = body.take(400), at = now.now())
                        dao.saveReceipt(id, AgentRules.receiptJson(receipt), remoteId, now.now())
                        // 服务端直接给完成状态时立即收尾；否则保持 Running 等回执 / 人工核实
                        AgentRules.mapRemoteStatus(remoteStatus)?.let { final ->
                            if (dao.finishRunning(id, final.name,
                                body.take(800), if (final == AgentTaskStatus.Failed) "服务端返回失败" else null, now.now()) > 0
                                && final == AgentTaskStatus.Succeeded) writeBackSource(task)
                        }
                        true
                    } else {
                        dao.scheduleRetry(id, "HTTP $code", retryAt(task.attempts), now.now())
                        dao.saveReceipt(id, AgentRules.receiptJson(
                            AgentReceipt(code, now.now() - started, note = body.take(200), at = now.now())), null, now.now())
                        false
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dao.scheduleRetry(id, e.javaClass.simpleName + (e.message?.let { ": ${it.take(80)}" } ?: ""),
                    retryAt(task.attempts), now.now())
                false
            }
        }
    }

    private fun retryAt(attempts: Int): Long? = AgentRules.nextRetryDelay(attempts)?.let { now.now() + it }

    private fun buildRequest(task: AgentTaskRow, endpoint: AgentEndpointRow): Request {
        val builder = Request.Builder().url(endpoint.url)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Resound-Task-Id", task.taskId)
            .header("X-Resound-Type", "agent_task")
            .post(task.payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        endpointSecret(endpoint).takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        runCatching {
            (app.resound.platform.SharedJson.parseToJsonElement(endpoint.headersJson) as? kotlinx.serialization.json.JsonObject)
                ?.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { builder.header(k, it) } }
        }
        return builder.build()
    }

    private suspend fun poll(task: AgentTaskRow, endpoint: AgentEndpointRow): Boolean {
        val url = endpoint.statusUrlTemplate.replace("{taskId}", task.remoteId ?: task.taskId)
        return try {
            val builder = Request.Builder().url(url).get()
            endpointSecret(endpoint).takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            val started = now.now()
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty().take(4000)
                val (_, remoteStatus) = AgentRules.remoteAck(body)
                dao.saveReceipt(task.id, AgentRules.receiptJson(
                    AgentReceipt(response.code, now.now() - started, remoteStatus = remoteStatus,
                        note = body.take(400), at = now.now())), null, now.now())
                val final = AgentRules.mapRemoteStatus(remoteStatus) ?: return@use false
                (dao.finishRunning(task.id, final.name, body.take(800),
                    if (final == AgentTaskStatus.Failed) "服务端返回失败" else null, now.now()) > 0)
                    .also { if (it && final == AgentTaskStatus.Succeeded) writeBackSource(task) }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** 后台周期驱动：WorkManager 每 15 分钟兜底；应用内每 60 秒补一次到期的。 */
    fun startDriver() {
        scope.launch {
            while (true) {
                runCatching { dispatchDue() }.onFailure { FailureNotes.record("agent/AgentBridge.kt:driver", it) }
                delay(60_000)
            }
        }
    }
}
