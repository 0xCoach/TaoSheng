package app.resound.capture

import android.media.AudioAttributes as A
import android.media.AudioDeviceInfo as D
import app.resound.audio.PlaybackConfigSnapshot
import app.resound.audio.playbackState
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    @Test fun headphonesPlayingMeansPlayingButNotSpeaker() {
        // 蓝牙耳机在放：playing=true、speakerPlaying=false（外接输出刻意不算外放）
        val bluetooth = playbackState(listOf(PlaybackConfigSnapshot(A.USAGE_MEDIA, D.TYPE_BLUETOOTH_A2DP)), listOf(D.TYPE_BUILTIN_SPEAKER), true)
        assertTrue(bluetooth.playing); assertFalse(bluetooth.speakerPlaying)
        // 拿不到路由但输出列表里有有线耳机，也不算外放
        val wired = playbackState(listOf(PlaybackConfigSnapshot(A.USAGE_MEDIA, null)), listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_WIRED_HEADPHONES), true)
        assertTrue(wired.playing); assertFalse(wired.speakerPlaying)
    }
    @Test fun speakerRouteMarksBothTrue() {
        val safe = playbackState(listOf(PlaybackConfigSnapshot(A.USAGE_MEDIA, D.TYPE_BUILTIN_SPEAKER_SAFE)), listOf(D.TYPE_BUILTIN_SPEAKER), true)
        assertTrue(safe.playing); assertTrue(safe.speakerPlaying)
        // 路由拿不到、输出只有内置扬声器：保守判外放（与改造前一致）
        val unknown = playbackState(listOf(PlaybackConfigSnapshot(A.USAGE_GAME, null)), listOf(D.TYPE_BUILTIN_SPEAKER), true)
        assertTrue(unknown.playing); assertTrue(unknown.speakerPlaying)
    }
    @Test fun stoppedBecomesAllFalseEvenWithLingeringPlayers() {
        // 暂停后播放器往往还挂着（配置仍在），必须跟随 isMusicActive 发布 false
        val configs = listOf(PlaybackConfigSnapshot(A.USAGE_MEDIA, D.TYPE_BUILTIN_SPEAKER))
        val stopped = playbackState(configs, listOf(D.TYPE_BUILTIN_SPEAKER), false)
        assertFalse(stopped.playing); assertFalse(stopped.speakerPlaying)
    }
    @Test fun noActiveConfigMeansAllFalse() {
        val s = playbackState(emptyList(), listOf(D.TYPE_BUILTIN_SPEAKER), true)
        assertFalse(s.playing); assertFalse(s.speakerPlaying)
    }
    @Test fun nonMediaUsageIsNotSpeakerMedia() {
        val s = playbackState(listOf(PlaybackConfigSnapshot(A.USAGE_ALARM, null)), listOf(D.TYPE_BUILTIN_SPEAKER), true)
        assertFalse(s.speakerPlaying)
    }
}
