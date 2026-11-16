package app.resound.screentime

/** 采集入口的开关协调：关闭时释放监听，重新开启时重新查询当前播放。 */
fun updatePlaybackCollection(enabled: Boolean, collecting: Boolean, start: () -> Unit, stop: () -> Unit) {
    if (enabled && !collecting) start()
    else if (!enabled && collecting) stop()
}
