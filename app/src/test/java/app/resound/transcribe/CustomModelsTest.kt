package app.resound.transcribe

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import app.resound.digest.*

class CustomModelsTest {
    @Test fun modelListFallsBackToUnversionedEndpoint()=runBlocking {
        val paths=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val path=chain.request().url.encodedPath;paths+=path
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(path=="/v1/models")404 else 200).message("test")
                .body("""{"data":[{"id":"voice-b"},{"id":"voice-a"}]}""".toResponseBody()).build()
        }.build()
        val result=fetchRemoteModels("https://example.com","test-key",client)
        assertEquals(listOf("/v1/models","/models"),paths);assertEquals("https://example.com",result.base);assertEquals(listOf("voice-a","voice-b"),result.ids)
    }
    @Test fun unauthorizedModelListDoesNotTryOtherPaths()=runBlocking {
        var calls=0
        val client=OkHttpClient.Builder().addInterceptor{chain->calls++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("test").body("secret-not-for-user".toResponseBody()).build()}.build()
        val error=runCatching{fetchRemoteModels("https://example.com","test-key",client)}.exceptionOrNull()
        assertNotNull(error);assertEquals(1,calls);assertFalse(error!!.message!!.contains("secret-not-for-user"))
    }
    @Test fun customChatUsesSamePathFallback()=runBlocking {
        val paths=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val path=chain.request().url.encodedPath;paths+=path
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(path=="/v1/chat/completions")404 else 200).message("test")
                .body("""{"choices":[{"message":{"content":"{}"}}]}""".toResponseBody()).build()
        }.build()
        val result=LlmClient(client).chatJson(LlmPreset("custom","自定义","https://example.com","model-a"),"test-key","test","test")
        assertEquals("{}",result.content);assertEquals(listOf("/v1/chat/completions","/chat/completions"),paths)
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
    }
}
