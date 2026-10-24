package app.resound.transcribe.local

import app.resound.voiceprint.SpeakerTurns
import kotlin.math.sqrt

object PauseChunks {
    const val LIMIT = 28 * 16000
    data class Chunk(val start: Int, val end: Int, val speaker: Int?, val overlapSamples: Int = 0, val groupStart: Int = start)
    fun split(pcm: ShortArray, turns: List<SpeakerTurns.Turn>): List<Chunk> = buildList {
        for (turn in turns) {
            var start=turn.start;var overlap=0
            while (turn.end-start>LIMIT) {
                val target=start+LIMIT
                val from=maxOf(start+16000,target-5*16000)
                var silenceStart=-1;var latestPause=-1
                var quiet=target-320;var quietEnergy=Double.MAX_VALUE
                for (at in from until target step 320) {
                    val end=minOf(at+320,target)
                    var energy=0.0
                    for(i in at until end) { val x=pcm[i]/32768.0;energy+=x*x }
                    val rms=sqrt(energy/(end-at))
                    if(rms<=quietEnergy){quietEnergy=rms;quiet=(at+end)/2}
                    if(rms<0.006) {
                        if(silenceStart<0)silenceStart=at
                        if(end-silenceStart>=3200)latestPause=(silenceStart+end)/2
                    } else silenceStart=-1
                }
                val cut=if(latestPause>start)latestPause else quiet
                add(Chunk(start,cut,turn.speaker,overlap,turn.start))
                overlap=if(latestPause>start)0 else 16000
                start=cut-overlap
            }
            if(turn.end>start)add(Chunk(start,turn.end,turn.speaker,overlap,turn.start))
        }
    }
}
