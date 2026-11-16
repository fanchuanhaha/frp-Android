package io.github.acedroidx.frp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.acedroidx.frp.ui.theme.FrpTheme
import io.github.acedroidx.frp.ui.theme.ThemeModeKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {
    private val isStartup = MutableStateFlow(false)
    private val configLists = MutableStateFlow<Map<FrpType, List<FrpConfig>>>(emptyMap())
    private val runningConfigList = MutableStateFlow<List<FrpConfig>>(emptyList())
    private val frpVersion = MutableStateFlow("")
    private val themeMode = MutableStateFlow("")
    private val configTemplates =
        MutableStateFlow<Map<FrpType, List<FrpConfigTemplate>>>(emptyMap())

    private lateinit var preferences: SharedPreferences

    private lateinit var mService: ShellService
    private var mBound: Boolean = false

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as ShellService.LocalBinder
            mService = binder.getService()
            mBound = true

            // 获取frp版本
            lifecycleScope.launch {
                try {
                    val frpcVersion = mService.getFrpVersion(FrpType.FRPC)
                    val frpsVersion = mService.getFrpVersion(FrpType.FRPS)
                    val version = if (frpcVersion == frpsVersion) {
                        frpcVersion
                    } else {
                        "frpc:$frpcVersion/frps:$frpsVersion"
                    }
                    frpVersion.value = version
                    // 存储到 SharedPreferences
                    preferences.edit {
                        putString(PreferencesKey.FRP_VERSION, version)
                    }
                } catch (_: Exception) {
                    frpVersion.value = "Error"
                    preferences.edit {
                        putString(PreferencesKey.FRP_VERSION, "Error")
                    }
                }
            }

            mService.lifecycleScope.launch {
                mService.processThreads.collect { processThreads ->
                    runningConfigList.value = processThreads.keys.toList()
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private val configActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            updateConfigList()
        }

    // Android 17+ 每次启动时检查局域网权限
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查是否需要退出应用
        if (intent.getBooleanExtra("EXIT_APP", false)) {
            finishAffinity() // 关闭所有 Activity
            return
        }

        preferences = getSharedPreferences("data", MODE_PRIVATE)

        // 首次启动引导页
        val isFirstLaunch = !preferences.getBoolean(PreferencesKey.FIRST_LAUNCH_DONE, false)
        if (isFirstLaunch) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Android 17 (SDK 37) 及以上每次启动时检查局域网权限
        if (Build.VERSION.SDK_INT >= 37) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_LOCAL_NETWORK
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }

        // 应用"最近任务中排除"设置
        val excludeFromRecents = preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val appTasks = am.appTasks
            if (appTasks.isNotEmpty()) {
                for (task in appTasks) {
                    task.setExcludeFromRecents(excludeFromRecents)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set excludeFromRecents: ${e.message}")
        }

        val loadingText = getString(R.string.loading)
        isStartup.value = preferences.getBoolean(PreferencesKey.AUTO_START, false)
        frpVersion.value =
            preferences.getString(PreferencesKey.FRP_VERSION, loadingText) ?: loadingText
        val rawTheme = preferences.getString(PreferencesKey.THEME_MODE, ThemeModeKeys.FOLLOW_SYSTEM)
        themeMode.value = ThemeModeKeys.normalize(rawTheme)

        checkConfig()
        configTemplates.value = getFrpConfigTemplates(this)
        updateConfigList()
        createBGNotificationChannel()

        if (preferences.getBoolean(PreferencesKey.AUTO_START_LAUNCH, false)) {
            maybeAutoStartOnLaunch()
        }

        enableEdgeToEdge()
        setContent {
            val currentTheme by themeMode.collectAsStateWithLifecycle(themeMode.collectAsState().value)
            val openDialog = remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }

            FrpTheme(themeMode = currentTheme) {
                val frpVersion by frpVersion.collectAsStateWithLifecycle(frpVersion.collectAsState().value.ifEmpty { loadingText })
                Scaffold(topBar = {
                    TopAppBar(title = {
                        Text(
                            stringResource(
                                R.string.frp_for_android_version,
                                BuildConfig.VERSION_NAME,
                                frpVersion
                            )
                        )
                    }, actions = {
                        IconButton(onClick = {
                            startActivity(
                                Intent(
                                    this@MainActivity, SettingsActivity::class.java
                                )
                            )
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings_24dp),
                                contentDescription = stringResource(R.string.content_desc_settings)
                            )
                        }
                    })
                }, floatingActionButton = {
                    FloatingActionButton(
                        onClick = { openDialog.value = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_add_24),
                            contentDescription = stringResource(R.string.addConfigButton),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }, snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                }) { contentPadding ->
                    // Screen content
                    Box(
                        modifier = Modifier
                            .padding(contentPadding)
                            .verticalScroll(rememberScrollState())
                            .scrollable(
                                orientation = Orientation.Vertical,
                                state = rememberScrollableState { delta -> 0f })
                    ) {
                        MainContent()
                    }
                }
                if (openDialog.value) {
                    CreateConfigDialog { openDialog.value = false }
                }
            }
        }

        if (!mBound) {
            val intent = Intent(this, ShellService::class.java)
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun MainContent() {
        val configLists by configLists.collectAsStateWithLifecycle(emptyMap())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (configLists.values.all { it.isEmpty() }) {
                Text(
                    stringResource(R.string.no_config),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            FrpType.entries.forEach { type ->
                val configs = configLists[type].orEmpty()
                if (configs.isNotEmpty()) {
                    Text(
                        typeLabel(type), style = MaterialTheme.typography.titleLarge
                    )
                    configs.forEach { config -> FrpConfigItem(config) }
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun FrpConfigItem(config: FrpConfig = FrpConfig(FrpType.FRPC, "test.toml")) {
        val runningConfigList by runningConfigList.collectAsStateWithLifecycle(emptyList())
        val isRunning = runningConfigList.contains(config)
        val startingConfigList by if (mBound) {
            mService.startingConfigs.collectAsStateWithLifecycle(emptySet())
        } else {
            remember { MutableStateFlow(emptySet<FrpConfig>()) }.collectAsStateWithLifecycle(
                emptySet()
            )
        }
        val isStarting = startingConfigList.contains(config)
        val isActive = isRunning || isStarting
        val showLog = remember { mutableStateOf(false) }
        val showDeleteDialog = remember { mutableStateOf(false) }

        // 监听实时配置日志
        val configLogs by if (mBound) {
            mService.configLogs.collectAsStateWithLifecycle(emptyMap())
        } else {
            remember { MutableStateFlow(emptyMap<FrpConfig, String>()) }.collectAsStateWithLifecycle(
                emptyMap()
            )
        }

        val configLog = configLogs[config] ?: ""

        // 初始化时加载日志
        LaunchedEffect(showLog.value, isRunning, isStarting, mBound) {
            if (showLog.value && mBound) {
                mService.getConfigLog(config)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), onClick = {
                    if (mBound) {
                        showLog.value = !showLog.value
                        if (showLog.value) {
                            mService.getConfigLog(config)
                        }
                    }
                }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                config.fileName,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            TypeBadge(config.type)
                        }
                        if (isActive) {
                            Text(
                                stringResource(
                                    if (isStarting) R.string.config_starting
                                    else R.string.config_running
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = if (showLog.value) "▲" else "▼",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickable(
                                enabled = !isActive,
                                onClick = { startConfigActivity(config) },
                                onLongClick = {
                                    // 通过 ContentProvider 让外部应用读写配置
                                    val uri =
                                        Uri.parse("content://${FrpConfigProvider.AUTHORITY}/${config.type.typeName}/${config.fileName}")
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "text/plain")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    }
                                    try {
                                        startActivity(viewIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.toast_no_viewer_for_config),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                })
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pencil_24dp),
                            contentDescription = stringResource(R.string.content_desc_edit),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            showDeleteDialog.value = true
                        }, enabled = !isActive, modifier = Modifier.size(32.dp, 28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_delete_24),
                            contentDescription = stringResource(R.string.content_desc_delete_config),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Switch(
                        checked = isActive,
                        enabled = !isStarting,
                        onCheckedChange = {
                            if (it) {
                                startShell(config)
                            } else {
                                stopShell(config)
                            }
                        })
                }
            }

            // 可折叠的日志视图
            AnimatedVisibility(
                visible = showLog.value, enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = 300, easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 300, easing = FastOutSlowInEasing
                    )
                ), exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 250, easing = FastOutSlowInEasing
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 250, easing = FastOutSlowInEasing
                    )
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.config_log_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { copyConfigLog(config, configLog) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    stringResource(R.string.copy),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = { exportConfigLog(config, configLog) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    stringResource(R.string.export_log),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (mBound) {
                                        mService.clearConfigLog(config)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    stringResource(R.string.deleteButton),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            SelectionContainer {
                                Text(
                                    text = configLog.ifEmpty {
                                        stringResource(R.string.no_log)
                                    },
                                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 删除确认对话框
        if (showDeleteDialog.value) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog.value = false },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(R.string.confirm_delete_message, config.fileName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteConfig(config)
                            showDeleteDialog.value = false
                        }) {
                        Text(stringResource(R.string.deleteConfigButton))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDialog.value = false }) {
                        Text(stringResource(R.string.dismiss))
                    }
                })
        }
    }

    private fun copyConfigLog(config: FrpConfig, log: String) {
        if (log.isBlank()) {
            Toast.makeText(this, getString(R.string.no_log), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(config.fileName, log))
        Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * 导出到 /sdcard/隧道名-启动器-日期.log；
     * 没有“所有文件访问权限”时回退到应用专属外部目录。
     */
    private fun exportConfigLog(config: FrpConfig, log: String) {
        if (log.isBlank()) {
            Toast.makeText(this, getString(R.string.no_log), Toast.LENGTH_SHORT).show()
            return
        }
        val tunnelName = sanitizeFileNamePart(config.fileName.substringBeforeLast('.'))
        val launcherName = sanitizeFileNamePart(config.type.getDisplayName(this))
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "$tunnelName-$launcherName-$date.log"
        val targets = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), fileName),
            getExternalFilesDir(null)?.let { File(it, fileName) },
        )
        var lastError: Exception? = null
        targets.forEach { target ->
            try {
                target.parentFile?.mkdirs()
                target.writeText(log)
                Toast.makeText(
                    this, getString(R.string.log_exported, target.absolutePath), Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        Toast.makeText(
            this,
            getString(R.string.log_export_failed, lastError?.message ?: ""),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun sanitizeFileNamePart(value: String): String {
        return value
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
            .ifEmpty { "log" }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Preview(showBackground = true)
    fun CreateConfigDialog(onClose: () -> Unit = {}) {
        val templates by configTemplates.collectAsStateWithLifecycle(emptyMap())
        val selectedType = remember { mutableStateOf(FrpType.FRPC) }

        LaunchedEffect(templates) {
            if (templates[selectedType.value].isNullOrEmpty()) {
                val fallbackType = FrpType.values().firstOrNull { !templates[it].isNullOrEmpty() }
                if (fallbackType != null) {
                    selectedType.value = fallbackType
                }
            }
        }

        BasicAlertDialog(onDismissRequest = { onClose() }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.create_config_template_title),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge
                    )
                    val tabItems = FrpType.entries.toList()
                    val selectedIndex = tabItems.indexOf(selectedType.value).coerceAtLeast(0)
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        tabItems.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = selectedIndex == index,
                                onClick = {
                                    selectedType.value = type
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index, count = tabItems.size
                                ),
                                label = {
                                    Text(
                                        typeShortLabel(type),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                })
                        }
                    }
                    val currentType = selectedType.value
                    if (currentType.isFrp()) {
                        val availableTemplates = templates[currentType].orEmpty()
                        if (availableTemplates.isEmpty()) {
                            Text(
                                stringResource(R.string.template_empty_message),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableTemplates.forEach { template ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.35f
                                            )
                                        ),
                                        onClick = {
                                            createConfigFromTemplate(template)
                                            onClose()
                                        }) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                template.displayName,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.template_file_name, template.fileName
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.template_type_label, when (template.type) {
                                                        FrpType.FRPC -> stringResource(R.string.frpc_label)
                                                        FrpType.FRPS -> stringResource(R.string.frps_label)
                                                        else -> template.type.typeName
                                                    }
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val initialContent = stringResource(
                            if (currentType == FrpType.MEFRP) {
                                R.string.mefrp_config_comment
                            } else {
                                R.string.cloudflare_config_comment
                            }
                        )
                        Text(
                            stringResource(
                                if (currentType == FrpType.MEFRP) {
                                    R.string.mefrp_create_hint
                                } else {
                                    R.string.cloudflare_create_hint
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                createConfigFromContent(currentType, initialContent)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.create_and_edit_button))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = { onClose() }) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 SharedPreferences 重新加载主题设置
        val savedTheme =
            preferences.getString(PreferencesKey.THEME_MODE, ThemeModeKeys.FOLLOW_SYSTEM)
        themeMode.value = ThemeModeKeys.normalize(savedTheme)

        // 重新应用"最近任务中排除"设置
        val excludeFromRecents = preferences.getBoolean(PreferencesKey.EXCLUDE_FROM_RECENTS, false)
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val appTasks = am.appTasks
            if (appTasks.isNotEmpty()) {
                for (task in appTasks) {
                    task.setExcludeFromRecents(excludeFromRecents)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set excludeFromRecents in onResume: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mBound) {
            unbindService(connection)
            mBound = false
        }
    }

    fun checkConfig() {
        FrpType.entries.forEach { type ->
            val dir = type.getDir(this)
            if (dir.exists() && !dir.isDirectory) {
                dir.delete()
            }
            if (!dir.exists()) dir.mkdirs()
        }
        val frpcDir = FrpType.FRPC.getDir(this)
        // v1.1旧版本配置迁移
        // 遍历文件夹内的所有文件
        this.filesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".toml")) {
                // 构建目标文件路径
                val destination = File(frpcDir, file.name)
                // 移动文件
                if (file.renameTo(destination)) {
                    Log.d("adx", "Moved: ${file.name} to ${destination.absolutePath}")
                } else {
                    Log.e("adx", "Failed to move: ${file.name}")
                }
            }
        }
    }

    private fun deleteConfig(config: FrpConfig) {
        val file = config.getFile(this)
        if (file.exists()) {
            file.delete()
        }
        updateConfigList()
    }

    private fun startConfigActivity(type: FrpType) {
        val targetTemplate = configTemplates.value[type]?.firstOrNull()
        if (targetTemplate != null) {
            createConfigFromTemplate(targetTemplate)
            return
        }
        createConfigFromAsset(type, type.getConfigAssetsName())
    }

    private fun createConfigFromTemplate(template: FrpConfigTemplate) {
        createConfigFromAsset(template.type, template.assetPath)
    }

    private fun createConfigFromAsset(type: FrpType, assetPath: String) {
        val currentDate = Date()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault())
        val formattedDateTime = formatter.format(currentDate)
        val fileName = "$formattedDateTime.toml"
        val targetDir = type.getDir(this)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val file = File(targetDir, fileName)
        assets.open(assetPath).use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        val config = FrpConfig(type, fileName)
        startConfigActivity(config)
    }

    private fun createConfigFromContent(type: FrpType, content: String) {
        val currentDate = Date()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault())
        val fileName = "${formatter.format(currentDate)}.toml"
        val targetDir = type.getDir(this)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        File(targetDir, fileName).writeText(content + "\n")
        startConfigActivity(FrpConfig(type, fileName))
    }

    private fun startConfigActivity(config: FrpConfig) {
        val intent = Intent(this, ConfigActivity::class.java)
        intent.putExtra(IntentExtraKey.FrpConfig, config)
        configActivityLauncher.launch(intent)
    }

    private fun startShell(configs: List<FrpConfig>) {
        if (configs.isEmpty()) return

        val intent = Intent(this, ShellService::class.java)
        intent.action = ShellServiceAction.START
        intent.putExtra(IntentExtraKey.FrpConfig, ArrayList(configs))
        startService(intent)
    }

    private fun startShell(config: FrpConfig) {
        startShell(listOf(config))
    }

    private fun stopShell(config: FrpConfig) {
        val intent = Intent(this, ShellService::class.java)
        intent.action = ShellServiceAction.STOP
        intent.putExtra(IntentExtraKey.FrpConfig, arrayListOf(config))
        startService(intent)
    }

    private fun createBGNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel("shell_bg", name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun maybeAutoStartOnLaunch() {
        val configList = AutoStartHelper.loadAutoStartConfigs(this)
        startShell(configList)
    }

    private fun updateConfigList() {
        val lists = FrpType.entries.associateWith { type ->
            type.getDir(this).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".toml") }
                ?.map { FrpConfig(type, it.name) }
                ?: emptyList()
        }
        configLists.value = lists

        // 检查自启动列表中是否含有已经删除的配置
        FrpType.entries.forEach { type ->
            val key = type.getAutoStartPreferencesKey()
            val validNames = lists[type].orEmpty().map { it.fileName }.toSet()
            val saved = preferences.getStringSet(key, emptySet()).orEmpty()
            val filtered = saved.filter { it in validNames }.toSet()
            if (filtered != saved) {
                preferences.edit { putStringSet(key, filtered) }
            }
        }
    }
}

@Composable
private fun typeLabel(type: FrpType): String {
    return stringResource(
        when (type) {
            FrpType.FRPC -> R.string.frpc_label
            FrpType.FRPS -> R.string.frps_label
            FrpType.MEFRP -> R.string.mefrp_label
            FrpType.CLOUDFLARE -> R.string.cloudflare_label
        }
    )
}

@Composable
private fun typeShortLabel(type: FrpType): String {
    return when (type) {
        FrpType.FRPC -> "frpc"
        FrpType.FRPS -> "frps"
        FrpType.MEFRP -> "MeFrp"
        FrpType.CLOUDFLARE -> "CF"
    }
}

@Composable
private fun TypeBadge(type: FrpType) {
    val containerColor = when (type) {
        FrpType.FRPC -> MaterialTheme.colorScheme.primaryContainer
        FrpType.FRPS -> MaterialTheme.colorScheme.tertiaryContainer
        FrpType.MEFRP -> MaterialTheme.colorScheme.secondaryContainer
        FrpType.CLOUDFLARE -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (type) {
        FrpType.FRPC -> MaterialTheme.colorScheme.onPrimaryContainer
        FrpType.FRPS -> MaterialTheme.colorScheme.onTertiaryContainer
        FrpType.MEFRP -> MaterialTheme.colorScheme.onSecondaryContainer
        FrpType.CLOUDFLARE -> MaterialTheme.colorScheme.onErrorContainer
    }
    Text(
        text = typeLabel(type),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}