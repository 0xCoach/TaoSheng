package app.resound.scheduling

import app.resound.push.AudioPush
import app.resound.push.PushChannel
import app.resound.push.PushKind
import app.resound.topic.TopicStatus

object BackgroundRefreshPolicy {
    fun topicNeedsTick(status: TopicStatus?) = status == TopicStatus.Running || status == TopicStatus.Paused
    fun audioNeedsTick(channels: List<PushChannel>) = channels.any {
        it.enabled && it.kind == PushKind.MarkdownFolder && it.subscription.audio != AudioPush.Off
    }
    // 原规则是严格小于截止时间，所以增加 1 毫秒，避免到边界后空转。
    fun failureDeadline(firstFailedAt: List<Long?>) = firstFailedAt.filterNotNull().minOrNull()?.plus(259_200_001)
    fun redoDeadline(createdAt: Long) = createdAt + 86_400_001
}
