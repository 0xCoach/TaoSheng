package app.resound.backup

import app.resound.ui.settings.*
import org.junit.Assert.*
import org.junit.Test

class BackupPresentationTest {
    private val summary = BackupSummary(3,900,100L,50L,true,false)
    @Test fun successStaysPrimaryAfterFailureForBothDestinations() {
        for(local in listOf(true,false)) {
            assertEquals("最近成功备份",backupHeadline(local,true,false,100,"网络失败"))
            assertEquals("尚无成功备份",backupHeadline(local,true,false,null,"网络失败"))
            assertEquals("正在备份…",backupHeadline(local,true,true,100,"旧错误"))
        }
    }
    @Test fun newerSuccessIsNotHiddenByCachedSummaryAndBytesStayPaired() {
        assertEquals(BackupSuccess(200,70),latestBackupSuccess(summary,200,70))
        assertEquals(BackupSuccess(100,50),latestBackupSuccess(summary,80,20))
        assertEquals(BackupSuccess(100,50),latestBackupSuccess(summary,null,null))
    }
    @Test fun repeatedEntryKeepsGoodCacheButDifferentAccountAndFolderCannotReuseIt() {
        val cache = BackupPageCache()
        val cloud = cache.cloud("a@example.com",true)
        cloud.acceptSummary(Result.success(summary))
        cloud.acceptSummary(Result.failure(IllegalStateException("offline")))
        assertSame(cloud,cache.cloud("a@example.com",true)); assertEquals(summary,cloud.summary)
        assertNull(cache.cloud("b@example.com",true).summary)
        val local = cache.local("folder")
        local.acceptSummary(Result.success(summary))
        assertSame(local,cache.local("folder"))
        cache.invalidateLocal() // 同名文件夹换 URI
        assertNull(cache.local("folder").summary)
        local.acceptSummary(Result.success(null))
        assertNull(local.summary) // 真正读成功且为空，应清掉旧概况
    }
}
