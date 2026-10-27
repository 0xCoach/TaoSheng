package app.resound.audio

/**
 * 麦克风礼让规则（纯逻辑，便于单元测试）。返回暂时收不到声音的原因，null 表示可以正常收音。
 * 只认系统的静音信号：别的 App 同时录音、微信等网络通话时，系统仍把声音给涛声就照常收（要录下所有交互）；
 * 系统把涛声静音（例如普通电话、前台 App 独占）时才算让出，此时收到的只是静音。
 */
object MicYield {
    /** 只有本次录音申请的通信模式可豁免，真实响铃/电话始终算通话。只在被静音时用来区分原因，不用来决定让不让。 */
    fun inCall(mode: Int, ownCommunication: Boolean): Boolean =
        mode == android.media.AudioManager.MODE_RINGTONE || mode == android.media.AudioManager.MODE_IN_CALL ||
            (mode == android.media.AudioManager.MODE_IN_COMMUNICATION && !ownCommunication)

    const val IN_CALL = "通话中"
    const val OTHER_APP = "别的应用在用麦克风"

    /** 被系统静音就丢弃声音、保持设备打开，等系统恢复；[inCall] 只在被静音时才去问。 */
    fun whileOpen(silenced: Boolean, inCall: () -> Boolean): String? = when {
        !silenced -> null
        inCall() -> IN_CALL
        else -> OTHER_APP
    }
}
