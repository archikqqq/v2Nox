package com.v2nox.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Main screen; the ViewModel owns VPN state and configuration mutations. */
@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnPermissionResult(context, result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(viewModel) {
        viewModel.permissionRequests.collect { permissionLauncher.launch(it) }
    }
    MainScreenContent(
        state = state,
        onToggle = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleVpn(context) },
        onSelect = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.selectConfig(it) },
        onDelete = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.deleteConfig(it) },
        onImport = viewModel::importFromClipboard,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScreenContent(
    state: MainUiState,
    onToggle: () -> Unit,
    onSelect: (ServerConfig) -> Unit,
    onDelete: (ServerConfig) -> Unit,
    onImport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("v2Nox", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf(String::isNotBlank)?.let(onImport)
                    }) { Text("▣", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            ConnectionHero(state, onToggle)
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Серверы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${state.savedConfigs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.savedConfigs.isEmpty()) {
                Text("Добавьте конфигурацию из буфера обмена", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(state.savedConfigs, key = { it.id }) { config ->
                        ServerCard(config, selected = state.activeConfig?.id == config.id, onSelect = { onSelect(config) }, onDelete = { onDelete(config) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHero(state: MainUiState, onToggle: () -> Unit) {
    val connected = state.vpnState == VpnState.CONNECTED
    val connecting = state.vpnState == VpnState.CONNECTING || state.vpnState == VpnState.STOPPING
    val transition = rememberInfiniteTransition(label = "connection pulse")
    val pulse by transition.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val corner by animateFloatAsState(if (connected) 92f else 32f, label = "button shape")
    val scheme = MaterialTheme.colorScheme
    val buttonColor = if (connected) scheme.primaryContainer else scheme.surfaceContainerHighest
    val contentColor = if (connected) scheme.onPrimaryContainer else scheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
            if (connecting) CircularProgressIndicator(Modifier.size(216.dp), strokeWidth = 3.dp)
            Button(
                onClick = onToggle,
                enabled = !connecting,
                modifier = Modifier.size(184.dp).scale(if (connecting) pulse else 1f),
                shape = if (connected) CircleShape else RoundedCornerShape(corner.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = contentColor),
                border = if (connected) null else BorderStroke(1.dp, scheme.outlineVariant),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 5.dp, pressedElevation = 1.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text(if (connected) "◈" else "⏻", style = MaterialTheme.typography.displayLarge) }
        }
        Spacer(Modifier.height(8.dp))
        Text(when (state.vpnState) {
            VpnState.CONNECTED -> "Подключено"
            VpnState.CONNECTING, VpnState.STOPPING -> "Подключение..."
            else -> "Отключено"
        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(color = scheme.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.padding(top = 12.dp)) {
            Text(state.activeConfig?.name ?: "Сервер не выбран", modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ServerCard(config: ServerConfig, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.pointerInput(config.id) {
        var distance = 0f
        detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> distance += amount }, onDragEnd = {
            if (kotlin.math.abs(distance) > 120f) onDelete()
            distance = 0f
        }, onDragCancel = { distance = 0f })
    }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("VLESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("${config.address}:${config.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onSelect)
            IconButton(onClick = onDelete) { Text("×", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
