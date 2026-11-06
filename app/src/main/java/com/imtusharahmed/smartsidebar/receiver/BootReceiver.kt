package com.imtusharahmed.smartsidebar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.imtusharahmed.smartsidebar.data.AppRepository
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.service.SidebarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: AppRepository by inject()
    private val ringBuffer: DiagnosticRingBuffer by inject()

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        ringBuffer.record("BOOT_RECEIVER", "Received broadcast action=$action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (!validActions.contains(action)) return

        if (!Settings.canDrawOverlays(context)) {
            ringBuffer.record("BOOT_RECEIVER", "SYSTEM_ALERT_WINDOW missing; cannot auto-launch")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isServiceEnabled = runCatching {
                    repository.isServiceEnabled().first()
                }.getOrDefault(false)

                if (isServiceEnabled) {
                    val serviceIntent = Intent(context, SidebarService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    ringBuffer.record("BOOT_RECEIVER", "Dispatched startForegroundService on $action")
                } else {
                    ringBuffer.record("BOOT_RECEIVER", "Service disabled in preferences; skipping auto-launch")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
