package app.resound.night
import kotlinx.serialization.Serializable
import kotlin.math.*

data class PersonalExample(val features: List<Double>,val yes: Boolean)
@Serializable data class PersonalWeights(val mean: List<Double>,val scale: List<Double>,val weights: List<Double>,val intercept: Double,
    val active: Boolean,val accuracy: Float,val ruleAccuracy: Float,val recall: Float,val yes: Int,val no: Int, val answers: Map<Long,Boolean>? = null)
object PersonalNightModel {
    fun probability(model: PersonalWeights,x: List<Double>): Double {
        require(x.size==model.weights.size)
        val z=model.intercept+x.indices.sumOf { model.weights[it]*(x[it]-model.mean[it])/model.scale[it] }
        return 1/(1+exp(-z.coerceIn(-40.0,40.0)))
    }
    fun permits(model: PersonalWeights?,features: List<Double>)=model==null || !model.active || probability(model,features)>=.5
    fun due(count: Int,lastCount: Int,at: Long,trainedAt: Long?,charging: Boolean,answerChanged: Boolean = false)=charging && (answerChanged || count-lastCount>=10 || trainedAt==null || at-trainedAt>=7*86_400_000L)
    private fun fit(rows: List<PersonalExample>): PersonalWeights {
        val n=rows.first().features.size
        require(rows.all { it.features.size==n && it.features.all(Double::isFinite) })
        val mean=List(n) {i->rows.map {it.features[i]}.average()}
        val scale=List(n) {i->sqrt(rows.sumOf {(it.features[i]-mean[i]).pow(2)}/rows.size).coerceAtLeast(1e-6)}
        val x=rows.map {r->DoubleArray(n) {i->(r.features[i]-mean[i])/scale[i]} }
        val weights=DoubleArray(n);var intercept=0.0
        // 固定步长/迭代次数和 L2，避免训练随留出反馈调参。
        repeat(160) {
            val gradient=DoubleArray(n);var bias=0.0
            rows.indices.forEach {j->val z=intercept+weights.indices.sumOf {weights[it]*x[j][it]};val p=1/(1+exp(-z.coerceIn(-40.0,40.0)));val error=p-if(rows[j].yes)1 else 0;bias+=error;weights.indices.forEach {gradient[it]+=error*x[j][it]} }
            val rate=.08/maxOf(1.0,sqrt(n.toDouble()))
            weights.indices.forEach {weights[it]-=rate*(gradient[it]/rows.size+.05*weights[it])};intercept-=rate*bias/rows.size
        }
        return PersonalWeights(mean,scale,weights.toList(),intercept,false,0f,0f,0f,rows.count {it.yes},rows.count {!it.yes})
    }
    fun train(rows: List<PersonalExample>): PersonalWeights? {
        val yes=rows.count {it.yes};val no=rows.size-yes
        if(yes<20 || no<10)return null
        // 分层、确定性分折；每折的标准化参数只从其训练部分计算。
        val fold=IntArray(rows.size);var y=0;var n=0
        rows.indices.forEach {fold[it]=if(rows[it].yes)y++%5 else n++%5}
        var correct=0;var truePositive=0
        repeat(5) {k->
            val model=fit(rows.filterIndexed {i,_->fold[i]!=k})
            rows.forEachIndexed {i,r->if(fold[i]==k) {val prediction=probability(model,r.features)>=.5;if(prediction==r.yes)correct++;if(prediction && r.yes)truePositive++}}
        }
        val accuracy=correct.toFloat()/rows.size;val baseline=yes.toFloat()/rows.size;val recall=truePositive.toFloat()/yes
        // 输入都是第一道规则已经认出的事件，基线对它们全部放行，基线召回为 1。
        return fit(rows).copy(active=accuracy-baseline>=.05f && recall>=.95f,accuracy=accuracy,ruleAccuracy=baseline,recall=recall)
    }
}
