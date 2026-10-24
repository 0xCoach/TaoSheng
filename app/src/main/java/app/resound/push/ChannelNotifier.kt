package app.resound.push

import kotlinx.coroutines.CancellationException

class ChannelNotifier(private val local: Notifier, private val channels: AndroidPushChannels) : Notifier {
    override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {
        send(title, body, deepLink, topic, remoteReview = null)
    }

    internal suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?,
        remoteReview: app.resound.review.DailyReview?) {
        var localError: Exception? = null
        try { local.send(title, body, deepLink, topic) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { localError = e }
        if (topic != null) channels.dispatch(PushMessage(remoteReview?.pushTitle() ?: title,
            remoteReview?.pushBody(PushFormat.Text) ?: body, topic, deepLink, System.currentTimeMillis(), remoteReview))
        localError?.let { throw it }
    }
}
