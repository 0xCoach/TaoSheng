package app.resound.audio

/** 系统回调不提供别的应用包名，但保留 session id。启动前登记，防止自身启动回调误判。
 * 最近的 id 保留到进程结束（有界），也覆盖 stop 后排队中的旧回调。 */
internal object OwnRecordingSessions {
    private val ids = linkedSetOf<Int>()
    @Synchronized fun add(id: Int) { ids.add(id); while (ids.size > 128) ids.remove(ids.first()) }
    @Synchronized fun contains(id: Int) = id in ids
}
