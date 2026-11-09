/*
 * [INPUT]: PNote 回调、用户控制意图、AppGraph 的采集与导入能力。
 * [OUTPUT]: 设备与 Wi-Fi 状态、实时码流、串行下载及经目录复核的卡内文件清理。
 * [POS]: 厂商 SDK 控制入口；命令先等待服务绑定，所有业务状态由串行队列处理。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Bundle
import app.resound.AppGraph
import app.resound.audio.InputChoice
import app.resound.importer.AndroidAudioImporter
import com.wind.pnote.ui.DeviceDataListener
import com.wind.pnote.ui.PNote
import com.wind.pnote.service.LongSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

internal sealed interface RecorderPacket {
    data class Audio(val bytes: ByteArray) : RecorderPacket
    data class Gap(val reason: String) : RecorderPacket
}

class SonicloudRecorder(private val context: Application, private val importer: AndroidAudioImporter) : RecorderController, DeviceDataListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pendingAudio = java.util.concurrent.atomic.AtomicInteger()
    private val overloaded = java.util.concurrent.atomic.AtomicBoolean()
    private var transferGeneration = 0L
    private var stoppingTransfer: String? = null
    private val prefs = context.getSharedPreferences("sonicloud", Context.MODE_PRIVATE)
    private val saved = prefs.getString("address", null)?.let { RecorderDevice(it, prefs.getString("name", "录音卡")!!) }
    private val mutable = MutableStateFlow(RecorderStatus(device = saved))
    override val status = mutable.asStateFlow()
    private val transfer = RecorderTransfer(context, importer)
    private var stream: Channel<RecorderPacket>? = null
    private val sdkBinding: RecorderSdkBinding = RecorderSdkBinding(context, this) { reason -> submit {
        mutable.update { it.copy(devices = emptyList()) }
        lost(reason)
    } }
    private var pendingConnect: RecorderDevice? = null
    private var wanted = false
    private var paused = false
    private var pauseOnDetach = false
    private var ownsRecording = false
    private var manualDisconnect = false
    private var reconnect: Job? = null
    private var setupConnection: Job? = null
    private var ready = false
    private var pendingStop = false
    private var scanTimer: Job? = null
    private var commandTimer: Job? = null
    private var downloadTimer: Job? = null
    private var listTimer: Job? = null
    private var listGeneration = 0L
    private var conversion: Job? = null
    private var lastBytes = 0L
    private var recordName = ""
    private val captured = prefs.getStringSet("captured", emptySet())!!.toMutableSet()
    private val queue = ArrayDeque<RecorderFile>()
    private var listBuffer = linkedMapOf<String, RecorderFile>()
    private var filesVerified = false
    private val deletion: RecorderDeletion = RecorderDeletion(scope, ::submit,
        send = { name -> sdk { PNote.deleteReordFile(name) } },
        refresh = { requestFiles() },
        failed = { message -> filesVerified = false; fail(message) })
    val deleteStatus = deletion.status

    fun deleteFiles(deviceKey: String, files: List<RecorderFile>) = submit {
        check(mutable.value.connected && ready && mutable.value.device?.key == deviceKey) { "录音卡已改变，请重新选择文件" }
        check(!deletion.active && !deletion.status.value.needsReconnect) { "请等待清理完成，异常时断开重连后重试" }
        check(filesVerified && !mutable.value.listing) { "请先刷新完整目录再清理" }
        check(!wanted && stream == null && mutable.value.recording == RecorderRecording.Stopped) { "请先停止聆听和卡内录音" }
        check(mutable.value.downloading == null && stoppingTransfer == null) { "请先结束文件传输" }
        check(!wifi.active) { "请先关闭 Wi-Fi 快传，再清理卡内文件" }
        val available = mutable.value.files.associateBy { it.name }
        check(files.isNotEmpty() && files.all { available[it.name]?.let { f -> f.size == it.size && f.seconds == it.seconds } == true }) {
            "文件目录已改变，请刷新后重新选择"
        }
        filesVerified = false
        mutable.update { it.copy(error = null) }
        deletion.start(files)
    }
    fun stopDeleting() = submit { deletion.stopRemaining() }
    private val wifi: RecorderWifi = RecorderWifi(scope, ::submit, AndroidRecorderWifiNetwork(context),
        command = { command ->
            when (command) {
                RecorderWifiCommand.Open -> sdk { PNote.openWiFi() }
                RecorderWifiCommand.Connect -> sdk { PNote.connectDeviceWiFi() }
                RecorderWifiCommand.Connected -> {
                    // shortcut: 固定版 SDK 无 SocketFactory 注入；升级 SDK 时复核此内部类。
                    LongSocketClient.getInstance().setMaxReconnectCount(0)
                }
                RecorderWifiCommand.Close -> {
                    // SDK 的 disconnect 会等待 connectLock，必须留在 IO 队列。
                    LongSocketClient.getInstance().setMaxReconnectCount(0).disconnect()
                    withContext(Dispatchers.Main.immediate) { PNote.disconnectDeviceWiFi() }
                    if (mutable.value.connected && RecorderPermissions.connected(context)) sdk { PNote.closeWiFi() }
                }
            }
        },
        failed = { message -> cancelTransfer(); fail(message) },
    )
    val wifiStatus = wifi.status

    fun connectWifi() = submit {
        check(!deletion.active) { "请先结束卡内清理" }
        check(mutable.value.connected && ready) { "请先连接录音卡并等待初始化完成" }
        check(!wanted && stream == null && mutable.value.recording == RecorderRecording.Stopped) { "请先停止聆听和卡内录音" }
        check(mutable.value.downloading == null && stoppingTransfer == null) { "请先结束文件传输" }
        check(RecorderPermissions.canWifi(context)) { "请允许 Wi-Fi 附近设备权限；Android 12 及以下需要精确位置权限" }
        check(context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true) { "请开启手机 Wi-Fi" }
        check(android.os.Build.VERSION.SDK_INT >= 33 ||
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true) { "请开启系统位置开关后重试快传" }
        mutable.update { it.copy(error = null) }
        wifi.open()
    }
    fun disconnectWifi() = submit { cancelTransfer(); wifi.stop() }

    init {
        scope.launch {
            for (command in commands) try { command() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(e.message ?: "录音卡操作失败")
            }
        }
        context.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) { if (++started == 1) submit { if (sdkBinding.isReady) sdk { PNote.sendAppShowState(1) } } }
            override fun onActivityStopped(a: android.app.Activity) { if (--started == 0) submit { if (sdkBinding.isReady) sdk { PNote.sendAppShowState(2) } } }
            override fun onActivityCreated(a: android.app.Activity, state: Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> submit { lost("蓝牙已关闭") }
                    BluetoothAdapter.STATE_ON -> submit { if (wanted && !manualDisconnect) mutable.value.device?.let { connectNow(it) } }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }
    private fun submit(block: suspend () -> Unit) {
        check(commands.trySend(block).isSuccess) { "录音卡控制队列已关闭" }
    }
    private fun audioCommand(block: suspend () -> Unit) {
        if (overloaded.get()) return
        if (pendingAudio.incrementAndGet() > 256) {
            pendingAudio.decrementAndGet()
            if (overloaded.compareAndSet(false, true)) submit {
                wanted = false
                stream?.close(IllegalStateException("音频处理过慢，请停止后重试"))
                cancelTransfer()
                if (mutable.value.connected) sdk { PNote.stopRecord() }
                fail("音频处理过慢，已停止传输，请重试")
            }
            return
        }
        submit { try { block() } finally { pendingAudio.decrementAndGet() } }
    }
    private suspend fun sdk(block: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        check(RecorderPermissions.connected(context)) { "请允许附近设备权限" }
        check(context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) { "请打开蓝牙" }
        sdkBinding.awaitReady()
        block()
    }
    private fun fail(message: String) {
        mutable.update { it.copy(error = message, scanning = false, listing = if (deletion.active) it.listing else false) }
        stream?.trySend(RecorderPacket.Gap(message))
    }
    override fun scan() = submit {
        pendingConnect = null; reconnect?.cancel()
        check(RecorderPermissions.canScan(context)) { "请允许蓝牙扫描权限" }
        mutable.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        sdk { PNote.startSearch() }
        scanTimer?.cancel()
        scanTimer = scope.launch { delay(15_000); stopScan() }
    }
    override fun stopScan() = submit {
        scanTimer?.cancel()
        if (pendingConnect != null) {
            pendingConnect = null; reconnect?.cancel(); manualDisconnect = true
            fail("已取消连接录音卡")
        }
        if (sdkBinding.isReady) sdk { PNote.stopSearch() }
        mutable.update { it.copy(scanning = false) }
    }
    override fun connect(device: RecorderDevice) = submit { connectNow(device) }
    private suspend fun connectNow(device: RecorderDevice) {
        check(!deletion.active) { "请先结束卡内清理" }
        require(BluetoothAdapter.checkBluetoothAddress(device.address)) { "录音卡蓝牙地址无效" }
        check(mutable.value.downloading == null) { "请先结束文件传输" }
        val old = mutable.value.device
        check(recorderMayConnect(old?.key, device.key, stream != null)) { "请先停止聆听再更换录音卡" }
        if (mutable.value.connected && old == device || pendingConnect?.key == device.key) return
        deletion.reset(); filesVerified = false
        wifi.stop()
        ready = false
        if (mutable.value.connected) {
            check(!wanted) { "请先停止聆听再更换录音卡" }
            sdk { PNote.closeConnect() }
        }
        manualDisconnect = false
        check(prefs.edit().putString("address", device.address).putString("name", device.name).commit())
        mutable.update { it.copy(device = device, connected = false, files = emptyList(), error = null, scanning = false, recording = RecorderRecording.Unknown) }
        RecorderService.start(context)
        scanTimer?.cancel(); reconnect?.cancel()
        try {
            if (mutable.value.devices.none { it.key == device.key }) {
                check(RecorderPermissions.canScan(context)) { "请允许蓝牙扫描权限以重新发现录音卡" }
                // SDK 只能连接当前扫描缓存中的设备；保存地址不能代替一次发现。
                pendingConnect = device
                mutable.update { it.copy(scanning = true, devices = emptyList()) }
                sdk { PNote.startSearch() }
            } else {
                pendingConnect = null
                sdk { PNote.stopSearch(); PNote.connectDevice(device.name, device.address) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lost(e.message ?: "连接失败，等待录音卡")
            return
        }
        reconnect?.cancel()
        reconnect = scope.launch { delay(20_000); submit {
            if (!mutable.value.connected) lost(if (pendingConnect != null) "未找到这张录音卡，请确认已开机且在附近" else "连接超时，等待录音卡")
        } }
    }
    override fun disconnect() = submit {
        manualDisconnect = true
        if (stream != null) AppGraph.capture.stop()
        wanted = false
        reconnect?.cancel()
        cancelTransfer()
        if (mutable.value.connected) {
            if (ownsRecording) { sdk { PNote.stopRecord() }; delay(50); ownsRecording = false }
            sdk { PNote.closeConnect() }
        }
        lost("录音卡已断开，请手动连接")
        RecorderService.stop(context)
    }
    private suspend fun lost(reason: String) {
        if (pendingConnect != null && sdkBinding.isReady) runCatching { sdk { PNote.stopSearch() } }
        pendingConnect = null; scanTimer?.cancel()
        deletion.abort(reason); filesVerified = false; listTimer?.cancel()
        ready = false; setupConnection?.cancel()
        wifi.stop()
        transferGeneration++
        conversion?.cancelAndJoin(); conversion = null
        commandTimer?.cancel()
        mutable.update { it.copy(connected = false, recording = RecorderRecording.Unknown, error = reason, listing = false, scanning = false) }
        stream?.trySend(RecorderPacket.Gap(reason))
        transfer.close()
        downloadTimer?.cancel()
        mutable.update { it.copy(downloading = null, queued = 0) }
        queue.clear()
        reconnect?.cancel()
        if (!manualDisconnect && wanted) reconnect = scope.launch {
            delay(5000)
            submit { mutable.value.device?.let { connectNow(it) } }
        }
    }
    internal suspend fun attach(channel: Channel<RecorderPacket>, key: String) {
        val done = CompletableDeferred<Unit>()
        submit {
            try {
                check(!deletion.active) { "请先结束卡内清理" }
                check(stream == null) { "录音卡已有音频订阅" }
                check(!wifi.active) { "请先关闭 Wi-Fi 快传再开始聆听" }
                check(mutable.value.device?.key == key) { "请先连接所选录音卡" }
                check(mutable.value.downloading == null) { "请先结束文件下载" }
                stream = channel; wanted = true; ownsRecording = true; pauseOnDetach = false
                overloaded.set(false)
                lastBytes = android.os.SystemClock.elapsedRealtime()
                channel.send(RecorderPacket.Gap("等待录音卡音频"))
                if (!mutable.value.connected) {
                    if (!manualDisconnect) connectNow(checkNotNull(mutable.value.device))
                } else if (!ready) {
                    // 连接初始化结束后会查询状态并启动所选来源。
                } else if (paused || mutable.value.recording == RecorderRecording.Paused) {
                    paused = false; sdk { PNote.continueRecord() }
                } else sdk { PNote.getRecordState() }
                done.complete(Unit)
            } catch (e: Exception) {
                if (stream === channel) { stream = null; wanted = false; ownsRecording = false }
                done.completeExceptionally(e)
            }
        }
        done.await()
    }
    internal suspend fun detach(channel: Channel<RecorderPacket>) {
        val done = CompletableDeferred<Unit>()
        submit {
            try {
                if (stream === channel) {
                    stream = null; wanted = false
                    reconnect?.cancel()
                    val keepPaused = pauseOnDetach
                    pauseOnDetach = false; paused = keepPaused
                    if (mutable.value.connected) sdk { if (keepPaused) PNote.pauseRecord() else PNote.stopRecord() }
                    else if (!keepPaused) pendingStop = true
                    ownsRecording = keepPaused
                }
            } finally { channel.close(); done.complete(Unit) }
        }
        done.await()
    }
    internal fun pauseIntent() = submit { pauseOnDetach = true; wanted = false }
    internal fun stopIntent() = submit {
        pauseOnDetach = false; paused = false; wanted = false; reconnect?.cancel()
        if (ownsRecording && stream == null) {
            if (mutable.value.connected) sdk { PNote.stopRecord() } else pendingStop = true
            ownsRecording = false
        }
    }
    override fun stopRecording() = submit {
        paused = false; wanted = false
        pauseOnDetach = false
        if (stream != null) AppGraph.capture.stop()
        else if (mutable.value.connected) { sdk { PNote.stopRecord() }; ownsRecording = false }
    }
    private suspend fun reconcileRecording() {
        if (!wanted || stream == null || !mutable.value.connected || !ready) return
        when (recorderResumeCommand(mutable.value.recording)) {
            RecorderCommand.Start -> sdk { PNote.startRecord() }
            RecorderCommand.Continue -> { paused = false; sdk { PNote.continueRecord() } }
            else -> Unit
        }
        commandTimer?.cancel()
        commandTimer = scope.launch {
            delay(10_000)
            submit { if (wanted && android.os.SystemClock.elapsedRealtime() - lastBytes > 10_000) fail("录音卡未返回音频，请检查卡状态后重试") }
        }
    }
    override fun refreshFiles() = submit {
        check(!deletion.active) { "正在清理并复核目录，请稍候" }
        check(mutable.value.connected && ready) { "请先连接录音卡并等待初始化完成" }
        check(!mutable.value.listing) { "正在读取目录，请稍候" }
        check(mutable.value.downloading == null) { "正在下载，请稍后刷新" }
        check(!wifi.active || wifi.ready) { "请等待 Wi-Fi 连接完成" }
        requestFiles()
    }
    private suspend fun requestFiles() {
        filesVerified = false
        listBuffer.clear()
        // 保留上一份完整目录，分片到齐后一次替换，避免选择和页码在读取中跳动。
        mutable.update { it.copy(listing = true, error = null) }
        armListTimeout()
        sdk { PNote.getRecordFileList() }
    }
    private fun armListTimeout() {
        listTimer?.cancel()
        val generation = ++listGeneration
        listTimer = scope.launch {
            delay(30_000)
            submit { if (generation == listGeneration && mutable.value.listing) {
                filesVerified = false
                deletion.timeout()
                fail("读取卡内目录超时，请重新连接后刷新")
            } }
        }
    }
    override fun download(files: List<RecorderFile>) = submit {
        check(!deletion.active && filesVerified && !mutable.value.listing) { "请先读取完整目录并结束卡内清理" }
        check(mutable.value.connected && ready) { "请先连接录音卡并等待初始化完成" }
        check(!wanted && mutable.value.recording == RecorderRecording.Stopped) { "请先停止录音并保存卡内文件" }
        check(mutable.value.downloading == null) { "已有下载任务" }
        check(stoppingTransfer == null) { "正在确认停止传输，请稍候或断开重连" }
        check(!wifi.active || wifi.ready) { "请等待 Wi-Fi 连接完成后下载" }
        overloaded.set(false)
        val available = mutable.value.files.associateBy { it.name }
        val device = checkNotNull(mutable.value.device)
        queue.addAll(files.distinctBy { it.name }.map { checkNotNull(available[it.name]) { "文件列表已改变，请刷新" } }
            .filterNot { transfer.received(device.key, it) })
        nextFile()
    }
    private suspend fun nextFile() {
        if (queue.isEmpty()) { mutable.update { it.copy(downloading = null, queued = 0) }; wifi.stop(); return }
        val file = queue.removeFirst()
        val offset = transfer.begin(checkNotNull(mutable.value.device).key, file)
        mutable.update { it.copy(downloading = file.name, percent = (offset * 100L / file.size).toInt(), queued = queue.size, error = null) }
        if (offset.toLong() == file.size) { finishTransfer(); return }
        sdk { PNote.startGetFile(file.name, offset) }
        armDownloadTimeout()
    }
    private fun armDownloadTimeout() {
        downloadTimer?.cancel()
        downloadTimer = scope.launch { delay(30_000); submit { cancelTransfer(); fail("录音卡下载超时，重新选择文件可续传") } }
    }
    private fun finishTransfer() {
        downloadTimer?.cancel()
        val generation = ++transferGeneration
        conversion = scope.launch(Dispatchers.IO) {
            try {
                transfer.complete()
                submit {
                    if (generation != transferGeneration) return@submit
                    conversion = null
                    val device = mutable.value.device
                    mutable.update { it.copy(percent = 100, files = it.files.map { f -> f.copy(imported = device != null && transfer.received(device.key, f)) }) }
                    nextFile()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                submit { if (generation != transferGeneration) return@submit; conversion = null; transfer.close(); queue.clear(); mutable.update { it.copy(downloading = null, queued = 0) }; wifi.stop(); fail(e.message ?: "文件转换失败") }
            }
        }
    }
    override fun cancelDownload() = submit { cancelTransfer() }
    private suspend fun cancelTransfer() {
        transferGeneration++
        downloadTimer?.cancel()
        conversion?.cancelAndJoin(); conversion = null
        transfer.current?.let { if (mutable.value.connected) {
            stoppingTransfer = it.name
            runCatching { sdk { PNote.stopGetFile(it.name) } }
        } }
        transfer.close(); queue.clear()
        mutable.update { it.copy(downloading = null, queued = 0) }
        wifi.stop()
    }

    override fun onDeviceDataEvent(data: String?) { if (data != null) submit {
        try { event(JSONObject(data)) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            deletion.abort("录音卡响应无法解析")
            throw e
        }
    } }
    override fun onDeviceRecordData(bytes: ByteArray?) {
        if (bytes == null) return
        val copy = bytes.copyOf()
        audioCommand {
            if (wanted) {
                lastBytes = android.os.SystemClock.elapsedRealtime()
                if (stream?.trySend(RecorderPacket.Audio(copy))?.isFailure == true) {
                    wanted = false
                    stream?.close(IllegalStateException("音频处理过慢，已停止卡录音"))
                    sdk { PNote.stopRecord() }
                    fail("音频处理过慢，已停止卡录音")
                }
            }
        }
    }
    override fun onDeviceFileData(bytes: ByteArray?) {
        if (bytes == null) return
        val copy = bytes.copyOf()
        audioCommand {
            if (transfer.current != null && conversion?.isActive != true) {
                try {
                    transfer.append(copy)
                    val size = transfer.current!!.size
                    val percent = (transfer.offset * 100 / size).toInt()
                    if (percent != mutable.value.percent) { transfer.checkpoint(); mutable.update { it.copy(percent = percent) } }
                    armDownloadTimeout()
                } catch (e: Exception) { cancelTransfer(); fail(e.message ?: "保存音频失败") }
            }
        }
    }
    private suspend fun event(json: JSONObject) {
        if (json.optInt("state", 0) != 0) {
            if (json.optInt("cmd") in listOf(4, 14)) deletion.abort("录音卡删除或目录命令失败")
            if (json.optInt("cmd") == 12) wifi.stop("录音卡 Wi-Fi 命令失败")
            fail("录音卡命令失败：${json.optString("cmd")}")
            return
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val recordingStarted = when (json.optInt("cmd")) {
            3 -> data.optInt("record_state", -1) == 1
            8 -> data.optInt("event", -1).let { it == 1 || it == 7 }
            9 -> data.optInt("recordState", -1) == 1
            else -> false
        }
        if (recordingStarted) deletion.abort("录音卡开始录音")
        if (recordingStarted && wifi.active) {
            cancelTransfer()
            fail("录音卡开始录音，已关闭 Wi-Fi 快传")
        }
        when (json.optInt("cmd")) {
            1 -> {
                val address = data.optString("address").uppercase()
                if (BluetoothAdapter.checkBluetoothAddress(address)) {
                    val device = RecorderDevice(address, data.optString("name").ifBlank { "录音卡" })
                    mutable.update { it.copy(devices = (it.devices.filterNot { d -> d.address == address } + device)) }
                    if (pendingConnect?.key == device.key && !manualDisconnect) {
                        pendingConnect = null
                        mutable.update { it.copy(device = device, scanning = false) }
                        sdk { PNote.stopSearch(); PNote.connectDevice(device.name, device.address) }
                    }
                }
            }
            2 -> {
                val address = data.optString("address")
                if (address.isNotBlank() && address != "null" && !address.equals(mutable.value.device?.address, true)) return
                if (recorderConnected(data.opt("connect_state"))) {
                    if (manualDisconnect) { sdk { PNote.closeConnect() }; return }
                    reconnect?.cancel(); stoppingTransfer = null
                    mutable.update { it.copy(connected = true, error = null) }
                    setupConnection?.cancel()
                    setupConnection = scope.launch {
                        // 厂商示例要求连接后等待服务稳定，连续控制命令间隔至少 50ms。
                        delay(2000)
                        submit {
                            if (!mutable.value.connected || manualDisconnect) return@submit
                            sdk { PNote.syncTime() }; delay(50)
                            sdk { PNote.getCBC() }; delay(50)
                            if (pendingStop) { sdk { PNote.stopRecord() }; pendingStop = false; delay(50) }
                            ready = true
                            sdk { PNote.getRecordState() }; delay(50)
                            sdk { PNote.getFileNameOnlyRecording() }
                        }
                    }
                } else lost("录音卡连接已断开，等待重连")
            }
            3 -> {
                val state = when (data.optInt("record_state", -1)) {
                    0 -> RecorderRecording.Stopped; 1 -> RecorderRecording.Recording; 2 -> RecorderRecording.Paused
                    else -> RecorderRecording.Unknown
                }
                mutable.update { it.copy(recording = state) }
                data.optString("fileName").takeIf { it.isNotBlank() }?.let { rememberLive(it) }
                if (state != RecorderRecording.Recording) stream?.trySend(RecorderPacket.Gap("录音卡已暂停或停止"))
            }
            4 -> {
                if (!mutable.value.connected || !mutable.value.listing) return
                armListTimeout()
                deletion.directoryProgress()
                for (file in recorderDirectoryChunk(json)) {
                    val device = mutable.value.device
                    listBuffer[file.name] = file.copy(imported = device != null && transfer.received(device.key, file),
                        capturedLive = device != null && captured.contains(device.key + "/" + file.name))
                }
                if (json.optInt("finish") == 1) {
                    listTimer?.cancel()
                    val files = listBuffer.values.toList()
                    filesVerified = true
                    mutable.update { it.copy(files = files, listing = false) }
                    deletion.listed(files)
                }
            }
            5 -> {
                if (stoppingTransfer != null) {
                    if (data.optString("name").let { it.isBlank() || it == stoppingTransfer } && data.optInt("record_file_state", -1) in 0..3) stoppingTransfer = null
                    return
                }
                val file = transfer.current ?: return
                if (data.optString("name").let { it.isNotBlank() && it != file.name }) return
                when (data.optInt("record_file_state", -1)) {
                    0 -> if (conversion?.isActive != true) finishTransfer()
                    1, 2, 3 -> { cancelTransfer(); fail("文件传输中断，请刷新后重试") }
                }
            }
            6 -> mutable.update { it.copy(battery = data.optInt("cbc").takeIf { v -> v in 0..100 }) }
            8 -> when (data.optInt("event")) {
                1 -> { if (mutable.value.downloading != null) cancelTransfer(); sdk { PNote.startBtnBackRecord() }; wanted = stream != null; paused = false; mutable.update { it.copy(recording = RecorderRecording.Recording) } }
                3 -> {
                    wanted = false; sdk { PNote.stopBtnBackRecord() }
                    mutable.update { it.copy(recording = RecorderRecording.Stopped) }
                    stream?.trySend(RecorderPacket.Gap("录音卡已停止"))
                    if (stream != null) AppGraph.capture.stop()
                }
                5 -> { sdk { PNote.pauseBtnBackRecord() }; paused = true; wanted = false
                    mutable.update { it.copy(recording = RecorderRecording.Paused) }; stream?.trySend(RecorderPacket.Gap("录音卡已暂停")) }
                7 -> { sdk { PNote.continueBtnBackRecord() }; paused = false; wanted = stream != null
                    mutable.update { it.copy(recording = RecorderRecording.Recording) } }
            }
            9 -> {
                mutable.update { it.copy(recording = recorderQueryState(data.optInt("recordState"), it.recording, paused)) }
                reconcileRecording()
            }
            11 -> data.optString("recordName").takeIf { it.isNotBlank() }?.let { rememberLive(it) }
            12 -> wifi.event(data.optInt("wifi_state", -1), data.optString("wifiName"))
            14 -> deletion.acknowledged(data.optInt("delete_state", -1) == 0)
        }
    }
    private fun rememberLive(name: String) {
        recordName = name
        if (!wanted) return
        mutable.value.device?.let { captured.add(it.key + "/" + name) }
        check(prefs.edit().putStringSet("captured", captured.toSet()).commit())
    }
    internal fun serviceStopped() = submit {
        manualDisconnect = true; reconnect?.cancel()
        cancelTransfer()
        if (sdkBinding.isReady && RecorderPermissions.connected(context)) runCatching { sdk { PNote.closeConnect() } }
        lost("录音卡连接服务已停止，请重新连接")
    }
}
