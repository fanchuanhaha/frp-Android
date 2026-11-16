package io.github.acedroidx.frp

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.Collections.emptyMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class ShellService : LifecycleService() {
    companion object {
        private const val MAX_LOG_LINES = 50
        private val PUBLIC_DNS = listOf(
            "223.5.5.5", "119.29.29.29", "180.76.76.76", "1.1.1.1", "8.8.8.8", "2400:3200::1"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val startingSet = Collections.synchronizedSet(mutableSetOf<FrpConfig>())

    private val _processThreads = MutableStateFlow(mutableMapOf<FrpConfig, ShellThread>())
    val processThreads = _processThreads.asStateFlow()

    // 正在探测 DNS/拉起进程的配置，用于界面立即反馈“启动中”
    private val _startingConfigs = MutableStateFlow<Set<FrpConfig>>(emptySet())
    val startingConfigs = _startingConfigs.asStateFlow()

    private fun tryMarkStarting(config: FrpConfig): Boolean {
        if (!startingSet.add(config)) {
            return false
        }
        _startingConfigs.update { it + config }
        return true
    }

    private fun clearStarting(config: FrpConfig) {
        startingSet.remove(config)
        _startingConfigs.update { it - config }
    }

    private fun isStarting(config: FrpConfig): Boolean {
        return startingSet.contains(config)
    }

    private val _logText = MutableStateFlow("")

    // 为每个配置创建一个日志流
    private val _configLogs = MutableStateFlow(mutableMapOf<FrpConfig, String>())
    val configLogs = _configLogs.asStateFlow()

    fun clearConfigLog(config: FrpConfig) {
        val logFile = config.getLogFile(this)
        if (logFile.exists()) {
            logFile.delete()
        }
        // 清空内存中的日志
        _configLogs.update { currentLogs ->
            currentLogs.toMutableMap().apply {
                put(config, "")
            }
        }
    }

    fun getConfigLog(config: FrpConfig): String {
        // 优先返回内存中的实时日志
        return _configLogs.value[config] ?: run {
            // 如果内存中没有，从文件读取
            val logFile = config.getLogFile(this)
            if (!logFile.exists()) {
                return ""
            }

            val lines = mutableListOf<String>()
            BufferedReader(FileReader(logFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lines.add(line!!)
                }
            }

            // 只返回最多50行
            val logContent = if (lines.size <= MAX_LOG_LINES) {
                lines.joinToString("\n")
            } else {
                lines.takeLast(MAX_LOG_LINES).joinToString("\n")
            }

            // 同时更新内存中的日志
            _configLogs.update { currentLogs ->
                currentLogs.toMutableMap().apply {
                    put(config, logContent)
                }
            }

            logContent
        }
    }

    fun getFrpVersion(type: FrpType): String {
        return try {
            val ainfo = packageManager.getApplicationInfo(
                packageName, PackageManager.GET_SHARED_LIBRARY_FILES
            )
            val command = listOf("${ainfo.nativeLibraryDir}/${type.getLibName()}", "-v")

            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()

            process.waitFor()

            // frp版本信息通常在stdout或stderr中，优先使用stdout
            if (output.isNotEmpty()) {
                // 提取版本号，通常格式为 "frpc version x.x.x" 或类似格式
                val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
                val match = versionRegex.find(output)
                match?.value ?: output.take(20) // 如果找不到版本号，返回前20个字符
            } else if (errorOutput.isNotEmpty()) {
                val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
                val match = versionRegex.find(errorOutput)
                match?.value ?: errorOutput.take(20)
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e("adx", "Failed to get frp version: ${e.message}")
            "Error"
        }
    }

    private fun appendToConfigLog(config: FrpConfig, logLine: String) {
        val logDir = config.getLogDir(this)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val logFile = config.getLogFile(this)
        val lines = mutableListOf<String>()

        // 读取现有日志
        if (logFile.exists()) {
            BufferedReader(FileReader(logFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lines.add(line!!)
                }
            }
        }

        // 添加新日志行
        lines.add(logLine)

        // 如果超过最大行数，删除最旧的行
        while (lines.size > MAX_LOG_LINES) {
            lines.removeAt(0)
        }

        // 写回文件
        FileWriter(logFile, false).use { writer ->
            lines.forEach { line ->
                writer.write(line + "\n")
            }
        }

        // 实时更新内存中的日志流
        val logContent = lines.joinToString("\n")
        _configLogs.update { currentLogs ->
            currentLogs.toMutableMap().apply {
                put(config, logContent)
            }
        }
    }

    // Binder given to clients
    private val binder = LocalBinder()

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder(), IBinder {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): ShellService = this@ShellService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // 处理 STOP_ALL action，不需要 frpConfig 参数
        if (intent?.action == ShellServiceAction.STOP_ALL) {
            // 停止所有正在运行的配置
            val allConfigs = _processThreads.value.keys.toList()
            for (config in allConfigs) {
                stopFrp(config)
            }

            // 停止前台服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
            stopSelf()

            Toast.makeText(this, getString(R.string.toast_all_configs_stopped), Toast.LENGTH_SHORT)
                .show()

            // 关闭应用
            val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("EXIT_APP", true)
                // 检查是否需要从最近任务中排除
                val preferences = getSharedPreferences("data", MODE_PRIVATE)
                val excludeFromRecents =
                    preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
                if (excludeFromRecents) {
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
            }
            startActivity(mainActivityIntent)

            return START_NOT_STICKY
        }

        val frpConfig: ArrayList<FrpConfig>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.extras?.getParcelableArrayList(
                    IntentExtraKey.FrpConfig, FrpConfig::class.java
                )
            } else {
                @Suppress("DEPRECATION") intent?.extras?.getParcelableArrayList(IntentExtraKey.FrpConfig)
            }
        if (frpConfig == null) {
            Log.e("adx", "frpConfig is null")
            Toast.makeText(this, getString(R.string.toast_frp_config_null), Toast.LENGTH_SHORT)
                .show()
            return START_NOT_STICKY
        }
        val hideServiceToast = getSharedPreferences("data", MODE_PRIVATE).getBoolean(
            PreferencesKey.HIDE_SERVICE_TOAST, false
        )
        when (intent?.action) {
            ShellServiceAction.START -> {
                for (config in frpConfig) {
                    startFrp(config)
                }
                if (!hideServiceToast) {
                    Toast.makeText(
                        this, getString(R.string.service_start_toast), Toast.LENGTH_SHORT
                    ).show()
                }
                startForeground(1, showNotification())
            }

            ShellServiceAction.STOP -> {
                for (config in frpConfig) {
                    stopFrp(config)
                }
                startForeground(1, showNotification())
                if (_processThreads.value.isEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION") stopForeground(true)
                    }
                    stopSelf()
                    if (!hideServiceToast) {
                        Toast.makeText(
                            this, getString(R.string.service_stop_toast), Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startFrp(config: FrpConfig) {
        Log.d("adx", "start config is $config")
        val dir = config.getDir(this)
        val file = config.getFile(this)
        if (!file.exists()) {
            Log.w("adx", "file is not exist,service won't start")
            Toast.makeText(this, getString(R.string.toast_config_not_exist), Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (_processThreads.value.contains(config)) {
            Log.w("adx", "frp is already running")
            showAlreadyRunningToast()
            return
        }
        if (!tryMarkStarting(config)) {
            Log.w("adx", "frp is starting")
            showAlreadyRunningToast()
            return
        }
        appendToConfigLog(config, "[APP] 正在启动 ${config.type.getDisplayName(this)} ...")

        val ainfo = packageManager.getApplicationInfo(
            packageName, PackageManager.GET_SHARED_LIBRARY_FILES
        )
        val binary = "${ainfo.nativeLibraryDir}/${config.type.getLibName()}"
        val workingDir = if (config.type.isFrp()) dir else filesDir
        workingDir.mkdirs()
        val caFile = ensureCaBundle()

        // MeFrp 与 Cloudflare 不使用 TOML 配置，而是从配置文件中解析启动参数
        val commandList = when (config.type) {
            FrpType.MEFRP -> {
                val args = LauncherArgs.parseMefrp(file.readText())
                if (args == null) {
                    clearStarting(config)
                    appendToConfigLog(config, "[APP] MeFrp 参数无效")
                    Toast.makeText(this, getString(R.string.toast_mefrp_invalid), Toast.LENGTH_LONG)
                        .show()
                    return
                }
                listOf(
                    binary,
                    "-n",
                    "--disable-log-color",
                    "-t",
                    args.token,
                    "-p",
                    args.port,
                )
            }

            FrpType.CLOUDFLARE -> {
                val token = LauncherArgs.parseCloudflareToken(file.readText())
                if (token == null) {
                    clearStarting(config)
                    appendToConfigLog(config, "[APP] Cloudflare token 无效")
                    Toast.makeText(
                        this, getString(R.string.toast_cloudflare_invalid), Toast.LENGTH_LONG
                    ).show()
                    return
                }
                // Android 上无法使用 `service install`，直接以前台进程方式运行隧道。
                // 移动网络下 UDP 容易被限制，固定使用 http2。
                listOf(
                    binary,
                    "tunnel",
                    "--no-autoupdate",
                    "run",
                    "--protocol",
                    "http2",
                    "--token",
                    token,
                )
            }

            else -> listOf(binary, "-c", config.fileName)
        }
        // 避免把 token 输出到日志
        Log.d("adx", "start ${config.type.typeName} in $workingDir")
        val env = mapOf(
            // Go 静态二进制无法读取 Android 系统证书，显式指定内置 CA
            "SSL_CERT_FILE" to caFile.absolutePath,
            "HOME" to filesDir.absolutePath,
            "TMPDIR" to cacheDir.absolutePath,
        )

        if (config.type.isFrp()) {
            try {
                val thread = runCommand(commandList, workingDir, config, env)
                _processThreads.update { it.toMutableMap().apply { put(config, thread) } }
            } catch (e: Exception) {
                Log.e("adx", e.stackTraceToString())
                appendToConfigLog(config, "[APP] 启动失败: ${e.message}")
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                stopSelf()
            } finally {
                clearStarting(config)
            }
            return
        }

        // 静态 Go 程序需要可用的 resolv.conf，网络探测放到后台线程避免阻塞主线程
        Thread {
            try {
                val dnsSetup = detectDnsSetup()
                writeResolvConf(workingDir, dnsSetup)
                if (!isStarting(config)) {
                    Log.d("adx", "start cancelled: $config")
                    return@Thread
                }
                val finalCommand = if (config.type == FrpType.CLOUDFLARE) {
                    // cloudflared 支持显式指定解析器，避免再次依赖系统 DNS 列表
                    commandList + dnsSetup.servers.take(3).flatMap { server ->
                        listOf("--dns-resolver-addrs", formatDnsAddress(server))
                    }
                } else {
                    commandList
                }
                Log.d(
                    "adx",
                    "start ${config.type.typeName}, dns=${dnsSetup.servers}, tcp=${dnsSetup.useTcp}"
                )
                appendToConfigLog(
                    config,
                    "[APP] DNS: ${dnsSetup.servers.joinToString()}" +
                        if (dnsSetup.useTcp) " (TCP)" else ""
                )
                val thread = runCommand(finalCommand, workingDir, config, env)
                _processThreads.update { it.toMutableMap().apply { put(config, thread) } }
            } catch (e: Exception) {
                Log.e("adx", e.stackTraceToString())
                appendToConfigLog(config, "[APP] 启动失败: ${e.message}")
                mainHandler.post {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            } finally {
                clearStarting(config)
            }
        }.start()
    }

    private fun showAlreadyRunningToast() {
        // Respect user preference to hide service start/stop related toasts
        val hideServiceToast = getSharedPreferences("data", MODE_PRIVATE).getBoolean(
            PreferencesKey.HIDE_SERVICE_TOAST, false
        )
        if (!hideServiceToast) {
            Toast.makeText(this, getString(R.string.toast_frp_running), Toast.LENGTH_SHORT).show()
        }
    }

    private data class DnsProbe(val server: String, val udpOk: Boolean, val tcpOk: Boolean)
    private data class DnsSetup(val servers: List<String>, val useTcp: Boolean)

    /**
     * 部分网络（尤其是开启 VPN 或分应用代理时）会下发不可达的 DNS。
     * 这里并行探测系统 DNS、网关和公共 DNS，选出真正可用的解析器。
     */
    private fun detectDnsSetup(): DnsSetup {
        val candidates = collectDnsCandidates()
        if (candidates.isEmpty()) {
            return DnsSetup(PUBLIC_DNS.take(3), false)
        }
        val executor = Executors.newFixedThreadPool(minOf(8, candidates.size))
        val futures = try {
            executor.invokeAll(
                candidates.map { server ->
                    Callable<DnsProbe> { probeDns(server) }
                },
                6,
                TimeUnit.SECONDS,
            )
        } finally {
            executor.shutdownNow()
        }
        val probes = futures.mapNotNull { future ->
            if (future.isCancelled) null else runCatching { future.get() }.getOrNull()
        }
        val udpWorking = probes.filter { it.udpOk }.map { it.server }
        if (udpWorking.isNotEmpty()) {
            return DnsSetup(udpWorking.take(3), false)
        }
        val tcpWorking = probes.filter { it.tcpOk }.map { it.server }
        if (tcpWorking.isNotEmpty()) {
            // 网络只放行 TCP 53 时强制 Go 使用 TCP 查询
            return DnsSetup(tcpWorking.take(3), true)
        }
        return DnsSetup(candidates.take(3), false)
    }

    private fun collectDnsCandidates(): List<String> {
        val systemServers = linkedSetOf<String>()
        try {
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            if (connectivityManager != null) {
                connectivityManager.allNetworks.forEach { network ->
                    val linkProperties = connectivityManager.getLinkProperties(network)
                        ?: return@forEach
                    linkProperties.dnsServers.forEach { address ->
                        address.hostAddress?.let { addDnsCandidate(systemServers, it) }
                    }
                    linkProperties.routes.forEach { route ->
                        val gateway = route.gateway
                        if (gateway != null && !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress) {
                            gateway.hostAddress?.let { addDnsCandidate(systemServers, it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("adx", "Failed to read dns servers: ${e.message}")
        }
        return (systemServers + PUBLIC_DNS).distinct()
    }

    private fun addDnsCandidate(target: MutableSet<String>, host: String) {
        if (host.isBlank() || host.contains('%')) return
        // 链路本地 IPv6 需要 scope id，不能直接写入 resolv.conf
        if (host.startsWith("fe80", ignoreCase = true)) return
        target.add(host)
    }

    private fun probeDns(server: String): DnsProbe {
        val address = try {
            InetAddress.getByName(server)
        } catch (e: Exception) {
            return DnsProbe(server, false, false)
        }
        val query = buildDnsQuery()
        val udpOk = try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                socket.send(DatagramPacket(query, query.size, address, 53))
                val response = DatagramPacket(ByteArray(1500), 1500)
                socket.receive(response)
            }
            true
        } catch (e: Exception) {
            false
        }
        if (udpOk) {
            return DnsProbe(server, true, true)
        }
        val tcpOk = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 53), 1200)
                socket.soTimeout = 1200
                val output = DataOutputStream(socket.getOutputStream())
                output.writeShort(query.size)
                output.write(query)
                output.flush()
                val input = DataInputStream(socket.getInputStream())
                val length = input.readUnsignedShort()
                if (length <= 0) {
                    throw IllegalStateException("empty dns response")
                }
                input.readFully(ByteArray(length))
            }
            true
        } catch (e: Exception) {
            false
        }
        return DnsProbe(server, false, tcpOk)
    }

    private fun buildDnsQuery(host: String = "www.baidu.com"): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeShort(0x1a2b)
            output.writeShort(0x0100)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(0)
            host.split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                output.writeByte(bytes.size)
                output.write(bytes)
            }
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(1)
        }
        return buffer.toByteArray()
    }

    private fun formatDnsAddress(server: String): String {
        return if (server.contains(':')) "[$server]:53" else "$server:53"
    }

    /**
     * 静态链接的 Go 程序在 Android 上没有 /etc/resolv.conf。
     * 内置二进制中该路径已被改为相对路径 data/resolv.conf，
     * 这里把探测到的可用 DNS 写入进程工作目录。
     */
    private fun writeResolvConf(dir: File, setup: DnsSetup) {
        val options = mutableListOf<String>()
        if (setup.useTcp) {
            options.add("use-vc")
        }
        options.add("timeout:2")
        options.add("attempts:1")
        val content = buildString {
            setup.servers.take(3).forEach { append("nameserver ").append(it).append('\n') }
            append("options ").append(options.joinToString(" ")).append('\n')
        }
        val resolvFile = File(dir, "data/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        val tempFile = File(resolvFile.parentFile, "resolv.conf.tmp")
        tempFile.writeText(content)
        if (!tempFile.renameTo(resolvFile)) {
            resolvFile.writeText(content)
            tempFile.delete()
        }
    }

    private fun ensureCaBundle(): File {
        val caFile = File(filesDir, "cacert.pem")
        if (!caFile.exists() || caFile.length() == 0L) {
            assets.open("cacert.pem").use { input ->
                caFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return caFile
    }

    private fun stopFrp(config: FrpConfig) {
        clearStarting(config)
        val thread = _processThreads.value[config]
//        thread?.interrupt()
        thread?.stopProcess()
        _processThreads.update {
            it.toMutableMap().apply { remove(config) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        startingSet.clear()
        _startingConfigs.value = emptySet()
        if (!_processThreads.value.isEmpty()) {
            _processThreads.value.forEach {
//                it.value.interrupt()
                it.value.stopProcess()
            }
            _processThreads.update { emptyMap() }
        }
    }

    private fun runCommand(
        command: List<String>,
        dir: File,
        config: FrpConfig,
        envp: Map<String, String> = emptyMap(),
    ): ShellThread {
        val processThread = ShellThread(command, dir, envp) { logLine ->
            _logText.value += logLine + "\n"
            appendToConfigLog(config, logLine)
        }
        processThread.start()
        return processThread
    }

    private fun showNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                // 检查是否需要从最近任务中排除
                val preferences = getSharedPreferences("data", MODE_PRIVATE)
                val excludeFromRecents =
                    preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
                if (excludeFromRecents) {
                    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        // 创建停止所有配置的 PendingIntent
        val stopAllIntent = Intent(this, ShellService::class.java).apply {
            action = ShellServiceAction.STOP_ALL
        }
        val stopAllPendingIntent = PendingIntent.getService(
            this, 0, stopAllIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.frp_notification_title)).setContentText(
                getString(
                    R.string.frp_notification_content, _processThreads.value.size
                )
            )
            //.setTicker("test")
            .setContentIntent(pendingIntent).addAction(
                R.drawable.ic_baseline_delete_24, getString(R.string.stop), stopAllPendingIntent
            )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            notification.build()
        }
    }
}