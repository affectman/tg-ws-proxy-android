package io.github.affectman.tgwsproxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.ui.home.HomeActions
import io.github.affectman.tgwsproxy.ui.home.HomeScreen
import io.github.affectman.tgwsproxy.ui.logs.LogsScreen
import io.github.affectman.tgwsproxy.ui.settings.SettingsActions
import io.github.affectman.tgwsproxy.ui.settings.SettingsScreen

private enum class Tab(val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Rounded.Home),
    SETTINGS(R.string.tab_settings, Icons.Rounded.Settings),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Rounded.ReceiptLong),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    // A binder call: read when the screen comes back (e.g. from the system dialog), not per frame.
    var batteryExempt by remember { mutableStateOf(SystemIntents.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = SystemIntents.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val text = when (m) {
                UiMessage.UpToDate -> res.getString(R.string.msg_up_to_date)
                is UiMessage.UpdateFailed -> res.getString(R.string.msg_update_failed, m.reason)
                UiMessage.Copied -> res.getString(R.string.msg_copied)
                UiMessage.NoTelegram -> res.getString(R.string.msg_no_telegram)
            }
            snackbar.showSnackbar(text)
        }
    }

    // Notifications are optional for a foreground service, but the status and
    // the Stop action live there, so ask once when the proxy is first turned on.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val toggle = {
        if (Build.VERSION.SDK_INT >= 33 && !engine.state.isActive &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.toggle()
    }
    fun copy(label: String, text: String) {
        if (!SystemIntents.copy(context, label, text)) vm.message(UiMessage.Copied)
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(if (tab == Tab.HOME) R.string.app_name else tab.title)) },
                actions = {
                    if (tab == Tab.LOGS) {
                        val logs by vm.logs.collectAsStateWithLifecycle()
                        IconButton(onClick = { copy("log", logs) }) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = { SystemIntents.share(context, res.getString(R.string.tab_logs), logs) }) {
                            Icon(Icons.Rounded.Share, stringResource(R.string.action_share))
                        }
                        IconButton(onClick = vm::clearLogs) {
                            Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.action_clear))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.title)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = settings ?: run {
            Box(Modifier.fillMaxSize())
            return@Scaffold
        }
        AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
            when (t) {
                Tab.HOME -> {
                    val stats by vm.stats.collectAsStateWithLifecycle()
                    val link by vm.link.collectAsStateWithLifecycle()
                    val needsRestart by vm.needsRestart.collectAsStateWithLifecycle()
                    val update by vm.pendingUpdate.collectAsStateWithLifecycle()
                    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
                    HomeScreen(
                        settings = current,
                        engine = engine,
                        stats = stats,
                        link = link,
                        needsRestart = needsRestart,
                        pendingUpdate = update,
                        downloadProgress = progress,
                        showBatteryHint = SystemIntents.isAggressiveOem && !batteryExempt,
                        actions = HomeActions(
                            toggle = toggle,
                            connectTelegram = {
                                link?.let { if (!SystemIntents.openTelegram(context, it)) vm.message(UiMessage.NoTelegram) }
                            },
                            copyLink = { link?.let { copy("tg proxy", it) } },
                            usePort = vm::usePort,
                            restart = vm::restart,
                            installUpdate = vm::installUpdate,
                            exemptBattery = { SystemIntents.requestIgnoreBatteryOptimizations(context) },
                        ),
                        contentPadding = padding,
                    )
                }
                Tab.SETTINGS -> {
                    val schema by vm.schema.collectAsStateWithLifecycle()
                    val needsRestart by vm.needsRestart.collectAsStateWithLifecycle()
                    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
                    val lastCheck by vm.lastUpdateCheck.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settings = current,
                        schema = schema,
                        needsRestart = needsRestart,
                        checkingUpdate = checking,
                        lastUpdateCheck = lastCheck,
                        batteryExempt = batteryExempt,
                        actions = SettingsActions(
                            update = vm::update,
                            regenerateSecret = vm::regenerateSecret,
                            copySecret = { copy("secret", it) },
                            checkUpdates = vm::checkUpdatesNow,
                            restart = vm::restart,
                        ),
                        contentPadding = padding,
                    )
                }
                Tab.LOGS -> {
                    val logs by vm.logs.collectAsStateWithLifecycle()
                    LogsScreen(logs, padding)
                }
            }
        }
    }
}
