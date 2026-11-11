package com.v2nox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class V2NoxVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = mutableState.asStateFlow()

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /** Safe to call from UI code; service operations are serialized on the IO dispatcher. */
    fun startVpn(config: ServerConfig) {
        serviceScope.launch {
            operationMutex.withLock {
                if (mutableState.value == VpnState.CONNECTED) stopVpnInternal()
                mutableState.value = VpnState.CONNECTING
                startForeground(NOTIFICATION_ID, buildNotification("Подключение…"))

                try {
                    val established = Builder()
                        .setSession(config.name)
                        .setMtu(1500)
                        .addAddress("10.0.0.2", 32)
                        .addRoute("0.0.0.0", 0)
                        .establish() ?: error("Не удалось создать VPN-интерфейс")

                    tunInterface = established

                    // TODO(libv2ray.aar): передать established.fileDescriptor и config
                    // в API ядра, запустить туннель и обработать его завершение/ошибку.

                    mutableState.value = VpnState.CONNECTED
                    updateNotification("Подключено: ${config.name}")
                } catch (exception: Exception) {
                    tunInterface?.close()
                    tunInterface = null
                    mutableState.value = VpnState.DISCONNECTED
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** Safe to call from UI code. */
    fun stopVpn() {
        serviceScope.launch {
            operationMutex.withLock { stopVpnInternal() }
        }
    }

    private fun stopVpnInternal() {
        if (mutableState.value == VpnState.DISCONNECTED) {
            stopSelf()
            return
        }
        mutableState.value = VpnState.STOPPING

        // TODO(libv2ray.aar): остановить ядро до закрытия принадлежащего ему TUN-дескриптора.
        runCatching { tunInterface?.close() }
        tunInterface = null
        mutableState.value = VpnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { tunInterface?.close() }
        tunInterface = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "v2Nox VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Состояние VPN-подключения" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("v2Nox")
            .setContentText(status)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val CHANNEL_ID = "v2nox_vpn"
        private const val NOTIFICATION_ID = 2101
    }
}
