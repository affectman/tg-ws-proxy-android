package io.github.affectman.tgwsproxy.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.affectman.tgwsproxy.BuildConfig
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.core.ConfigField
import io.github.affectman.tgwsproxy.core.FieldKind
import io.github.affectman.tgwsproxy.data.AppSettings
import io.github.affectman.tgwsproxy.data.BatteryProfile
import io.github.affectman.tgwsproxy.ui.SystemIntents
import io.github.affectman.tgwsproxy.ui.home.NoticeCard
import java.text.DateFormat
import java.util.Date

data class SettingsActions(
    val update: ((AppSettings) -> AppSettings) -> Unit,
    val regenerateSecret: () -> Unit,
    val copySecret: (String) -> Unit,
    val checkUpdates: () -> Unit,
    val restart: () -> Unit,
)

/** ProxyConfig fields with hand-made controls; the rest go to "Advanced". */
private val CURATED = setOf(
    "secret", "port", "pool_size", "fake_tls_domain", "fallback_cfproxy", "cfproxy_h2_media",
    "cfproxy_user_domains", "cfproxy_worker_domains", "dc_redirects",
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    schema: List<ConfigField>,
    needsRestart: Boolean,
    checkingUpdate: Boolean,
    lastUpdateCheck: Long,
    batteryExempt: Boolean,
    actions: SettingsActions,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    var editing by remember { mutableStateOf<EditRequest?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val defaults = remember(schema) { schema.associate { it.name to it.default } }

    fun valueOf(name: String): Any? = settings.override(name) ?: defaults[name]
    fun boolOf(name: String) = valueOf(name) as? Boolean ?: false
    fun entriesOf(name: String): List<String> = (valueOf(name) as? List<*>)?.map { it.toString() } ?: emptyList()
    fun set(name: String, value: Any?) = actions.update { it.withOverride(name, value) }

    LazyColumn(contentPadding = contentPadding) {
        if (needsRestart) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    NoticeCard(
                        icon = Icons.Rounded.Refresh,
                        text = stringResource(R.string.restart_needed),
                        action = stringResource(R.string.action_restart),
                        onAction = actions.restart,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        section(R.string.section_battery) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BatteryProfile.entries.forEachIndexed { i, profile ->
                            SegmentedButton(
                                selected = settings.profile == profile,
                                onClick = { actions.update { it.copy(profile = profile) } },
                                shape = SegmentedButtonDefaults.itemShape(i, BatteryProfile.entries.size),
                            ) { Text(stringResource(profileLabel(profile))) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(profileDescription(settings.profile)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.set_idle_screen_off),
                    summary = stringResource(R.string.set_idle_screen_off_desc),
                    checked = settings.idleWhenScreenOff,
                ) { v -> actions.update { it.copy(idleWhenScreenOff = v) } }
            }
            item {
                ClickRow(
                    title = stringResource(R.string.set_battery_exempt),
                    summary = stringResource(
                        if (batteryExempt) R.string.set_battery_exempt_on else R.string.set_battery_exempt_off
                    ),
                ) { SystemIntents.requestIgnoreBatteryOptimizations(context) }
            }
        }

        section(R.string.section_connection) {
            item {
                ClickRow(stringResource(R.string.set_port), settings.port.toString()) {
                    editing = EditRequest.Text(
                        title = res.getString(R.string.set_port),
                        initial = settings.port.toString(),
                        hint = res.getString(R.string.set_port_hint),
                        keyboard = KeyboardType.Number,
                        validate = { it.toIntOrNull() in 1024..65535 },
                        onSave = { v -> actions.update { it.copy(port = v.toInt()) } },
                    )
                }
            }
            item {
                ClickRow(
                    title = stringResource(R.string.set_secret),
                    summary = settings.secret.take(6) + "••••••••" + settings.secret.takeLast(4),
                    mono = true,
                    trailing = {
                        IconButton(onClick = {
                            editing = EditRequest.Confirm(
                                title = res.getString(R.string.set_secret_regen_title),
                                text = res.getString(R.string.set_secret_regen_text),
                                confirm = res.getString(R.string.action_regenerate),
                                onConfirm = actions.regenerateSecret,
                            )
                        }) { Icon(Icons.Rounded.RestartAlt, stringResource(R.string.action_regenerate)) }
                    },
                ) { actions.copySecret(settings.secret) }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.set_start_on_boot),
                    summary = stringResource(R.string.set_start_on_boot_desc),
                    checked = settings.startOnBoot,
                ) { v -> actions.update { it.copy(startOnBoot = v) } }
            }
        }

        section(R.string.section_bypass) {
            item {
                val domain = valueOf("fake_tls_domain")?.toString().orEmpty()
                ClickRow(
                    stringResource(R.string.set_fake_tls),
                    domain.ifEmpty { stringResource(R.string.value_off) },
                ) {
                    editing = EditRequest.Text(
                        title = res.getString(R.string.set_fake_tls),
                        initial = domain,
                        hint = res.getString(R.string.set_fake_tls_hint),
                        keyboard = KeyboardType.Uri,
                        onSave = { set("fake_tls_domain", it.ifEmpty { null }) },
                    )
                }
            }
            item {
                SwitchRow(
                    stringResource(R.string.set_cfproxy),
                    stringResource(R.string.set_cfproxy_desc),
                    boolOf("fallback_cfproxy"),
                ) { set("fallback_cfproxy", it) }
            }
            item {
                SwitchRow(
                    stringResource(R.string.set_h2),
                    stringResource(R.string.set_h2_desc),
                    boolOf("cfproxy_h2_media"),
                ) { set("cfproxy_h2_media", it) }
            }
            listOf(
                Triple("cfproxy_user_domains", R.string.set_cf_domains, R.string.set_cf_domains_hint),
                Triple("cfproxy_worker_domains", R.string.set_cf_workers, R.string.set_cf_workers_hint),
                Triple("dc_redirects", R.string.set_dc_ip, R.string.set_dc_ip_hint),
            ).forEach { (name, title, hint) ->
                item {
                    val entries = entriesOf(name)
                    ClickRow(
                        stringResource(title),
                        if (entries.isEmpty()) stringResource(R.string.value_auto) else entries.joinToString(", "),
                    ) {
                        editing = EditRequest.Lines(
                            title = res.getString(title),
                            initial = entries,
                            hint = res.getString(hint),
                            onSave = { set(name, it) },
                        )
                    }
                }
            }
        }

        section(R.string.section_logs) {
            item {
                SwitchRow(
                    stringResource(R.string.set_verbose),
                    stringResource(R.string.set_verbose_desc),
                    settings.verboseLogs,
                ) { v -> actions.update { it.copy(verboseLogs = v) } }
            }
            item {
                SwitchRow(
                    stringResource(R.string.set_log_file),
                    stringResource(R.string.set_log_file_desc),
                    settings.logToFile,
                ) { v -> actions.update { it.copy(logToFile = v) } }
            }
        }

        section(R.string.section_updates) {
            item {
                SwitchRow(
                    stringResource(R.string.set_check_updates),
                    stringResource(R.string.set_check_updates_desc),
                    settings.checkUpdates,
                ) { v -> actions.update { it.copy(checkUpdates = v) } }
            }
            item {
                ClickRow(
                    title = stringResource(R.string.set_check_now),
                    summary = if (lastUpdateCheck > 0) stringResource(
                        R.string.set_last_check,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastUpdateCheck)),
                    ) else stringResource(R.string.set_never_checked),
                    trailing = { if (checkingUpdate) CircularProgressIndicator(Modifier.padding(8.dp)) },
                ) { actions.checkUpdates() }
            }
        }

        val advanced = schema.filter { it.name !in CURATED && it.kind != FieldKind.UNKNOWN }
        if (advanced.isNotEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.section_advanced), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(R.string.section_advanced_desc)) },
                    trailingContent = {
                        Icon(if (advancedOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    },
                    modifier = Modifier.clickable { advancedOpen = !advancedOpen },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
            if (advancedOpen) {
                advanced.forEach { field ->
                    item(key = "adv-${field.name}") {
                        AdvancedRow(
                            field = field,
                            value = valueOf(field.name),
                            overridden = settings.override(field.name) != null,
                            onEdit = { editing = it },
                            onSet = { set(field.name, it) },
                        )
                    }
                }
            }
        }

        section(R.string.section_about) {
            item {
                ClickRow(
                    stringResource(R.string.about_version),
                    stringResource(R.string.about_version_value, BuildConfig.VERSION_NAME, BuildConfig.CORE_VERSION),
                ) { SystemIntents.openUrl(context, "https://github.com/${BuildConfig.GITHUB_REPO}/releases") }
            }
            item {
                ClickRow(stringResource(R.string.about_upstream), "github.com/Flowseal/tg-ws-proxy") {
                    SystemIntents.openUrl(context, "https://github.com/Flowseal/tg-ws-proxy")
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { request -> EditDialog(request) { editing = null } }
}

private fun LazyListScope.section(title: Int, content: LazyListScope.() -> Unit) {
    item {
        Column {
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            Text(
                stringResource(title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
    }
    content()
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ClickRow(
    title: String,
    summary: String?,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let {
            { Text(it, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 2) }
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** A control picked by the field's type, so new upstream options need no code. */
@Composable
private fun AdvancedRow(
    field: ConfigField,
    value: Any?,
    overridden: Boolean,
    onEdit: (EditRequest) -> Unit,
    onSet: (Any?) -> Unit,
) {
    val res = LocalResources.current
    val title = advancedLabel(field.name)?.let { stringResource(it) } ?: humanize(field.name)
    val reset: (@Composable () -> Unit)? = if (overridden) {
        { IconButton(onClick = { onSet(null) }) { Icon(Icons.Rounded.RestartAlt, stringResource(R.string.action_reset)) } }
    } else null
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        when (field.kind) {
            FieldKind.BOOL -> ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(field.name, fontFamily = FontFamily.Monospace) },
                trailingContent = { Switch(checked = value as? Boolean ?: false, onCheckedChange = { onSet(it) }) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            FieldKind.STR_LIST, FieldKind.DC_MAP -> {
                val entries = (value as? List<*>)?.map { it.toString() } ?: emptyList()
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(entries.joinToString(", ").ifEmpty { "—" } + "\n" + field.name, fontFamily = FontFamily.Monospace) },
                    trailingContent = reset,
                    modifier = Modifier.clickable {
                        onEdit(EditRequest.Lines(title, entries, onSave = { onSet(it) }))
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            else -> {
                val text = value?.toString().orEmpty()
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(text.ifEmpty { "—" } + "\n" + field.name, fontFamily = FontFamily.Monospace) },
                    trailingContent = reset,
                    modifier = Modifier.clickable {
                        onEdit(
                            EditRequest.Text(
                                title = title,
                                initial = text,
                                hint = res.getString(R.string.hint_default_value, field.default?.toString() ?: "—"),
                                keyboard = when (field.kind) {
                                    FieldKind.INT -> KeyboardType.Number
                                    FieldKind.FLOAT -> KeyboardType.Decimal
                                    else -> KeyboardType.Text
                                },
                                validate = { v ->
                                    when (field.kind) {
                                        FieldKind.INT -> v.toIntOrNull() != null
                                        FieldKind.FLOAT -> v.toDoubleOrNull() != null
                                        else -> true
                                    }
                                },
                                onSave = { v ->
                                    onSet(
                                        when (field.kind) {
                                            FieldKind.INT -> v.toInt()
                                            FieldKind.FLOAT -> v.toDouble()
                                            else -> v
                                        }
                                    )
                                },
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
    }
}

private fun advancedLabel(name: String): Int? = when (name) {
    "host" -> R.string.adv_host
    "buffer_size" -> R.string.adv_buffer_size
    "disable_secure" -> R.string.adv_disable_secure
    "proxy_protocol" -> R.string.adv_proxy_protocol
    "force_test_dc" -> R.string.adv_force_test_dc
    else -> null
}

private fun humanize(name: String): String =
    name.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun profileLabel(p: BatteryProfile) = when (p) {
    BatteryProfile.SPEED -> R.string.profile_speed
    BatteryProfile.BALANCED -> R.string.profile_balanced
    BatteryProfile.SAVER -> R.string.profile_saver
}

private fun profileDescription(p: BatteryProfile) = when (p) {
    BatteryProfile.SPEED -> R.string.profile_speed_desc
    BatteryProfile.BALANCED -> R.string.profile_balanced_desc
    BatteryProfile.SAVER -> R.string.profile_saver_desc
}
