package app.resound.audio

/** 麦克风礼让规则（纯逻辑，便于单元测试）。返回暂时收不到声音的原因，null 表示可以正常收音。 */
object MicYield {
    const val IN_CALL = "通话中"
    const val OTHER_APP = "别的应用在用麦克风"

    /** 打开麦克风之前：通话中或有别的 App 正在录音（未被静音）就等，不去抢。 */
    fun beforeOpen(inCall: Boolean, othersRecording: Int): String? = when {
        inCall -> IN_CALL
        othersRecording > 0 -> OTHER_APP
        else -> null
    }

    /** 已打开时：被系统静音或通话中就丢弃声音、保持设备打开，等系统恢复。 */
    fun whileOpen(inCall: Boolean, silenced: Boolean): String? = when {
        inCall -> IN_CALL
        silenced -> OTHER_APP
        else -> null
    }
}
