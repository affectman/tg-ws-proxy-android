package io.github.affectman.tgwsproxy.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.core.EngineState
import io.github.affectman.tgwsproxy.core.ProxyState
import io.github.affectman.tgwsproxy.core.ProxyStats
import io.github.affectman.tgwsproxy.data.AppSettings
import io.github.affectman.tgwsproxy.data.BatteryProfile
import io.github.affectman.tgwsproxy.data.UpdateInfo
import io.github.affectman.tgwsproxy.service.ProxyService
import io.github.affectman.tgwsproxy.ui.describeError
import io.github.affectman.tgwsproxy.ui.formatBytes
import io.github.affectman.tgwsproxy.ui.formatUptime

data class HomeActions(
    val toggle: () -> Unit,
    val connectTelegram: () -> Unit,
    val copyLink: () -> Unit,
    val usePort: (Int) -> Unit,
    val restart: () -> Unit,
    val installUpdate: (UpdateInfo) -> Unit,
    val exemptBattery: () -> Unit,
)

@Composable
fun HomeScreen(
    settings: AppSettings,
    engine: EngineState,
    stats: ProxyStats,
    link: String?,
    needsRestart: Boolean,
    pendingUpdate: UpdateInfo?,
    downloadProgress: Float?,
    showBatteryHint: Boolean,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(pendingUpdate != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            pendingUpdate?.let { UpdateCard(it, downloadProgress) { actions.installUpdate(it) } }
        }

        HeroCard(settings, engine, stats, actions.toggle)

        AnimatedVisibility(needsRestart, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            NoticeCard(
                icon = Icons.Rounded.Refresh,
                text = stringResource(R.string.restart_needed),
                action = stringResource(R.string.action_restart),
                onAction = actions.restart,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        val error = engine.error
        AnimatedVisibility(engine.state == ProxyState.ERROR && error != null) {
            if (error != null) {
                val port = error.suggestedPort
                NoticeCard(
                    icon = Icons.Rounded.ErrorOutline,
                    text = describeError(LocalContext.current, error),
                    action = port?.let { stringResource(R.string.action_use_port, it) },
                    onAction = { port?.let(actions.usePort) },
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        ConnectCard(link, actions.connectTelegram, actions.copyLink)

        AnimatedVisibility(engine.state == ProxyState.RUNNING, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            StatsGrid(stats)
        }

        if (showBatteryHint) {
            NoticeCard(
                icon = Icons.Rounded.BatterySaver,
                text = stringResource(R.string.hint_battery_exempt),
                action = stringResource(R.string.action_allow),
                onAction = actions.exemptBattery,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroCard(settings: AppSettings, engine: EngineState, stats: ProxyStats, onToggle: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerButton(
                state = engine.state,
                onToggle = onToggle,
                contentDescription = stringResource(
                    if (engine.state.isActive) R.string.action_turn_off else R.string.action_turn_on
                ),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(
                    when (engine.state) {
                        ProxyState.RUNNING -> R.string.state_running
                        ProxyState.STARTING -> R.string.state_starting
                        ProxyState.ERROR -> R.string.state_error
                        ProxyState.STOPPED -> R.string.state_stopped
                    }
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            val subtitle = when (engine.state) {
                ProxyState.RUNNING -> stringResource(R.string.hero_running_for, formatUptime(context, stats.uptimeSeconds))
                ProxyState.STARTING -> stringResource(R.string.hero_starting)
                ProxyState.ERROR -> stringResource(R.string.hero_error)
                ProxyState.STOPPED -> stringResource(R.string.hero_stopped)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("${ProxyService.LOOPBACK}:${settings.port}")
                Chip(
                    stringResource(
                        when (settings.profile) {
                            BatteryProfile.SPEED -> R.string.profile_speed
                            BatteryProfile.BALANCED -> R.string.profile_balanced
                            BatteryProfile.SAVER -> R.string.profile_saver
                        }
                    ) + if (engine.state == ProxyState.RUNNING && stats.idle) " · " + stringResource(R.string.chip_idle) else ""
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun ConnectCard(link: String?, onConnect: () -> Unit, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.connect_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                enabled = link != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_connect_telegram))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCopy,
                enabled = link != null,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_copy_link))
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: ProxyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Rounded.Hub, stats.active.toString(), stringResource(R.string.stat_active), Modifier.weight(1f))
            StatTile(Icons.Rounded.SwapVert, stats.total.toString(), stringResource(R.string.stat_total), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Rounded.ArrowUpward, formatBytes(stats.bytesUp), stringResource(R.string.stat_up), Modifier.weight(1f))
            StatTile(Icons.Rounded.ArrowDownward, formatBytes(stats.bytesDown), stringResource(R.string.stat_down), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo, progress: Float?, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
                    Text(info.versionName, style = MaterialTheme.typography.bodyMedium)
                }
                if (progress == null) {
                    FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.action_install)) }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun NoticeCard(
    icon: ImageVector,
    text: String,
    action: String?,
    onAction: () -> Unit,
    container: Color,
    content: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) {
                TextButton(onClick = onAction) { Text(action, color = content) }
            }
        }
    }
}
