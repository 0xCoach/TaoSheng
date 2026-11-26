package app.resound.audio

/**
 * 播放状态基础层（不落库、无开关、不需要任何权限）：
 * [playing] 表示任何媒体在出声（含耳机、蓝牙）；[speakerPlaying] 表示在出声且可能走扬声器，
 * 即录音切段用的现有外放判定。录音输入侧今后订阅它决定是否给蓝牙麦克风让路。
 */
data class PlaybackState(val playing: Boolean, val speakerPlaying: Boolean)

/** 一次系统播放配置的纯数据快照；[routedType] 是已知的输出路由类型，普通 App 常拿不到，为 null。 */
data class PlaybackConfigSnapshot(val usage: Int, val routedType: Int?)

// 以下原始值与 android.media.AudioAttributes / AudioDeviceInfo 的公开常量一致
// （USAGE_UNKNOWN=0、USAGE_MEDIA=1、USAGE_GAME=14；TYPE_BUILTIN_EARPIECE=1、
// TYPE_BUILTIN_SPEAKER=2、TYPE_WIRED_HEADPHONES=4、TYPE_BLUETOOTH_A2DP=8、
// TYPE_BUILTIN_SPEAKER_SAFE=24、TYPE_BLE_HEADSET=26、TYPE_TELEPHONY=18）。
// app 侧 PlaybackPolicyTest 用真实安卓常量走同一条判定路径锁住这些值，改动必须两边同步。
private const val USAGE_UNKNOWN = 0
private const val USAGE_MEDIA = 1
private const val USAGE_GAME = 14
private const val TYPE_BUILTIN_SPEAKER = 2
private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
private const val TYPE_BUILTIN_EARPIECE = 1
private const val TYPE_TELEPHONY = 18
private const val TYPE_UNKNOWN = 0

/** 该播放用途是否可能从扬声器出声；耳机、蓝牙等外接输出在放时不算外放。 */
fun speakerMedia(usage: Int, routedType: Int?, outputs: List<Int>): Boolean {
    if (usage != USAGE_MEDIA && usage != USAGE_GAME && usage != USAGE_UNKNOWN) return false
    // #59：路由没解析出来时系统给 TYPE_UNKNOWN(0)，和拿不到一样走下面的输出设备判断（魅族上外放抖音因此一直不算外放）
    if (routedType != null && routedType != TYPE_UNKNOWN) return isSpeaker(routedType)
    // 普通 App 常拿不到播放路由；保守地把所有外接输出排除，避免误跳过耳机时的人声。
    var foundSpeaker = false
    for (output in outputs) {
        when {
            isSpeaker(output) -> foundSpeaker = true
            output != TYPE_BUILTIN_EARPIECE && output != TYPE_TELEPHONY -> return false
        }
    }
    return foundSpeaker
}

private fun isSpeaker(type: Int) = type == TYPE_BUILTIN_SPEAKER || type == TYPE_BUILTIN_SPEAKER_SAFE

/** 由活动播放配置与 isMusicActive 算出播放状态；没有活动播放配置时不判在放，全 false。 */
fun playbackState(configs: List<PlaybackConfigSnapshot>, outputs: List<Int>, musicActive: Boolean, mediaVolume: Int = 1): PlaybackState {
    if (!musicActive || configs.isEmpty()) return PlaybackState(playing = false, speakerPlaying = false)
    val speaker = mediaVolume > 0 && configs.any { speakerMedia(it.usage, it.routedType, outputs) }
    return PlaybackState(playing = true, speakerPlaying = speaker)
}
