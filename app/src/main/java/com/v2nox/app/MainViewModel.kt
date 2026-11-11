package com.v2nox.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

data class MainUiState(
    val vpnState: VpnState = VpnState.DISCONNECTED,
    val activeConfig: ServerConfig? = null,
    val savedConfigs: List<ServerConfig> = emptyList(),
    val errorMessage: String? = null,
)

class MainViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()

    private val mutablePermissionRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<Intent> = mutablePermissionRequests.asSharedFlow()

    private var service: V2NoxVpnService? = null
    private var boundContext: Context? = null
    private var pendingConfig: ServerConfig? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val instance = (binder as? V2NoxVpnService.LocalBinder)?.service ?: return
            service = instance
            viewModelScope.launch {
                instance.state.collect { value -> mutableUiState.value = mutableUiState.value.copy(vpnState = value) }
            }
            pendingConfig?.also { pendingConfig = null; instance.startVpn(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    fun importFromClipboard(text: String) {
        runCatching { VlessParser.parse(text) }
            .onSuccess { parsed ->
                val existing = mutableUiState.value.savedConfigs
                if (existing.none { it.rawUrl == parsed.rawUrl }) mutableUiState.value = mutableUiState.value.copy(
                    savedConfigs = existing + parsed,
                    activeConfig = mutableUiState.value.activeConfig ?: parsed,
                    errorMessage = null,
                )
            }
            .onFailure { mutableUiState.value = mutableUiState.value.copy(errorMessage = it.message ?: "Не удалось прочитать конфигурацию") }
    }

    fun selectConfig(config: ServerConfig) {
        mutableUiState.value = mutableUiState.value.copy(activeConfig = config, errorMessage = null)
    }

    fun deleteConfig(config: ServerConfig) {
        val current = mutableUiState.value
        val updated = current.savedConfigs.filterNot { it.id == config.id }
        mutableUiState.value = current.copy(savedConfigs = updated, activeConfig = if (current.activeConfig?.id == config.id) updated.firstOrNull() else current.activeConfig)
    }

    fun toggleVpn(context: Context) {
        val state = mutableUiState.value
        if (state.vpnState == VpnState.CONNECTED || state.vpnState == VpnState.CONNECTING) {
            if (service != null) service?.stopVpn() else context.startService(Intent(context, V2NoxVpnService::class.java).setAction(V2NoxVpnService.ACTION_STOP))
            return
        }
        val config = state.activeConfig ?: run {
            mutableUiState.value = state.copy(errorMessage = "Сначала выберите сервер")
            return
        }
        pendingConfig = config
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent != null) mutablePermissionRequests.tryEmit(permissionIntent)
        else onVpnPermissionResult(context, granted = true)
    }

    fun onVpnPermissionResult(context: Context, granted: Boolean) {
        if (!granted) {
            pendingConfig = null
            mutableUiState.value = mutableUiState.value.copy(errorMessage = "Разрешение VPN не предоставлено")
            return
        }
        val intent = Intent(context, V2NoxVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        if (service == null && boundContext == null) {
            boundContext = context.applicationContext
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } else pendingConfig?.also { service?.startVpn(it); pendingConfig = null }
    }

    override fun onCleared() {
        boundContext?.unbindService(connection)
        boundContext = null
        super.onCleared()
    }
}

/** Parses the common vless://uuid@host:port?security=...#name form. */
object VlessParser {
    fun parse(text: String): ServerConfig {
        val link = text.trim().lineSequence().firstOrNull { it.trim().startsWith("vless://", ignoreCase = true) }?.trim()
            ?: error("В буфере не найден vless:// конфиг")
        val uri = URI(link)
        require(uri.scheme.equals("vless", ignoreCase = true)) { "Поддерживаются только VLESS-конфигурации" }
        val host = uri.host ?: error("В конфигурации отсутствует адрес сервера")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Некорректный порт сервера")
        val name = uri.rawFragment?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.takeIf { it.isNotBlank() } ?: host
        val security = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "security" }?.get(1)
        }
        return ServerConfig(UUID.randomUUID().toString(), name, host, port, link, security)
    }
}
