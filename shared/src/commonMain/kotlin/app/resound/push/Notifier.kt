package app.resound.push

interface Notifier {
    /** topic 决定还要转发到哪些推送通道；本地通知照常发 */
    suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic? = null)
}
