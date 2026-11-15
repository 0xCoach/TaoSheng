package app.resound.devices

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.work.await
import app.resound.AppGraph
import app.resound.devices.android.DeviceInputs
import kotlinx.coroutines.*

/** Explicit USB-host IPC. Exposes no records/files and grants no deletion eligibility. */
class DeviceArchiveBackupProvider : ContentProvider() {
    override fun onCreate() = true // Providers are created before Application.onCreate.

    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        val app = requireNotNull(context).applicationContext
        // ContentProvider.call does not enforce per-operation permissions for us.
        app.enforceCallingPermission(Manifest.permission.DUMP,"Authorized USB debugging host required")
        require(method == "backup-ready" && arg == null && (extras == null || extras.isEmpty))
        // The Binder thread can wait for cold Application initialization without blocking its main thread.
        return runBlocking(Dispatchers.IO) {
            withTimeout(10_000) {
                while (!AppGraph.isDevicesReady || !AppGraph.isBackupReady) delay(25)
                val devices = AppGraph.devices as? DeviceInputs
                val enabled = devices?.archiveMaintenanceEnabled() == true
                if (enabled) DeviceRetentionScheduler.backupAvailable(app).await()
                Bundle().apply { putInt("protocol",1);putBoolean("enabled",enabled);putBoolean("queued",enabled) }
            }
        }
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("No archive data exposed")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri,values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
