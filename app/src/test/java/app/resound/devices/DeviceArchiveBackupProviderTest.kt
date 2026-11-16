package app.resound.devices

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DeviceArchiveBackupProviderTest {
    private fun provider(authorized: Boolean): DeviceArchiveBackupProvider {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun enforceCallingPermission(permission: String,message: String?) {
                assertEquals(Manifest.permission.DUMP,permission)
                if (!authorized) throw SecurityException("Not an authorized host")
            }
        }
        return DeviceArchiveBackupProvider().apply {
            attachInfo(context,ProviderInfo().apply { authority="test.device-archive-backup";exported=true })
        }
    }
    @Test fun callerPermissionIsCheckedBeforeAnyMethodOrApplicationState() {
        val provider = provider(false)
        assertThrows(SecurityException::class.java) { provider.call("backup-ready",null,null) }
        assertThrows(SecurityException::class.java) { provider.call("other",null,null) }
    }
    @Test fun authorizedHostCannotSupplyEligibilityOrReadWriteArchiveData() {
        val provider = provider(true)
        assertThrows(IllegalArgumentException::class.java) { provider.call("other",null,null) }
        assertThrows(IllegalArgumentException::class.java) {
            provider.call("backup-ready",null,Bundle().apply { putBoolean("delete",true) })
        }
        val uri = Uri.parse("content://test.device-archive-backup/anything")
        assertThrows(UnsupportedOperationException::class.java) { provider.query(uri,null,null,null,null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri,null,null) }
    }
}
