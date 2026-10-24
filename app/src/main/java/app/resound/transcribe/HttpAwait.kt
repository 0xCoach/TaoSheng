package app.resound.transcribe

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitResponse():Response=suspendCancellableCoroutine { cont->
    cont.invokeOnCancellation{cancel()}
    enqueue(object:Callback {
        override fun onFailure(call:Call,e:IOException){if(cont.isActive)cont.resumeWithException(e)}
        override fun onResponse(call:Call,response:Response){
            cont.resume(response,onCancellation={_,value,_->value.close()})
        }
    })
}
