package app.resound.skills.core

import app.resound.skills.*
import app.resound.platform.NowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object SkillDrafting {
    suspend fun draft(
        conversation: List<DraftTurn>, clock: NowMillis, appName: String,
        model: () -> SkillModel, usage: suspend (String, Long, Long) -> Unit,
    ): Result<DraftStep> {
        if (conversation.firstOrNull { it.fromUser }?.text.isNullOrBlank()) return Result.failure(IllegalArgumentException("先描述一下想要什么"))
        val mustFinish = conversation.count { it.fromUser } > SkillDrafter.MAX_ASKS
        val selected = model()
        if (!selected.ready) return Result.failure(IllegalStateException("还没填写大模型密钥"))
        val id = "my_" + (clock.now() / 1000).toString(36)
        for (attempt in 0..1) {
            try {
                val reply = selected.request(SkillDrafter.system(appName), SkillDrafter.user(conversation))
                withContext(NonCancellable) { usage(SkillSchedule.date(clock.now()).toString(), reply.promptTokens, reply.completionTokens) }
                return Result.success(SkillDrafter.step(reply.content, id, mustFinish))
            } catch (e: CancellationException) { throw e }
            catch (e: SkillCallException) {
                if (e.kind != "Format") return Result.failure(IllegalStateException(e.detail))
            } catch (_: Exception) { /* 模型输出不合格式，重试一次 */ }
        }
        return Result.failure(IllegalStateException("没生成成功，换个说法再试一次"))
    }
}
