package app.resound.night
import kotlinx.serialization.Serializable
@Serializable data class CandidateWindow(val at: Long,val features: NightFeatures)
@Serializable data class CandidateFeatures(val windows: List<CandidateWindow>)
/** 只依据响度取前后各 2 秒；最多 60 秒，每个窗口只存一份。 */
class NightCandidates {
    private var previous: NightWindow?=null
    private val pending=mutableListOf<NightWindow>()
    private var tail=0
    fun accept(window: NightWindow): List<List<NightWindow>> {
        val result=mutableListOf<List<NightWindow>>()
        val hit=window.features.aboveBackground>=NightTuning.CANDIDATE_DB
        if(hit && pending.isEmpty())previous?.takeIf { it.start+2_000==window.start }?.let { pending+=it }
        if(hit || tail>0 || pending.isNotEmpty()) {
            pending+=window
            tail=if(hit)1 else (tail-1).coerceAtLeast(0)
            if(pending.size>=30 || !hit && tail==0) {result+=pending.toList();pending.clear()}
        }
        previous=window
        return result
    }
    fun finish()=pending.toList().also { pending.clear();previous=null;tail=0 }
}
