package app.resound.ui.verify

import androidx.compose.runtime.Composable
import app.resound.devices.DeviceState

/** Optional settings entries registered by adapters at application assembly. */
internal object DeviceOptionsUi {
    private val entries = mutableMapOf<String, @Composable (DeviceState) -> Unit>()
    fun register(card: String, content: @Composable (DeviceState) -> Unit) { entries[card] = content }
    @Composable fun Content(device: DeviceState) { entries[device.cardId]?.invoke(device) }
}
