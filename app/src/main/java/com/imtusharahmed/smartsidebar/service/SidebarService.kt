package com.imtusharahmed.smartsidebar.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.imtusharahmed.smartsidebar.MainActivity
import com.imtusharahmed.smartsidebar.R
import com.imtusharahmed.smartsidebar.data.AppRepository
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.model.PersistedSidebarConfig
import com.imtusharahmed.smartsidebar.overlay.OverlayWindowManager
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SidebarService : Service() {

    private val repository: AppRepository by inject()
    private val ringBuffer: DiagnosticRingBuffer by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("SideBar:SidebarService")
    )

    private var overlayWindowManager: OverlayWindowManager? = null
    private var serviceStartEpochMs: Long = 0L
    private var isExplicitlyStopped: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceStartEpochMs = System.currentTimeMillis()
        ensureNotificationChannel()
        inspectPreviousProcessExitReason()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            isExplicitlyStopped = true
            ringBuffer.record("FGS_LIFECYCLE", "Received explicit stop action")
            stopSelf()
            return START_NOT_STICKY
        }
        isExplicitlyStopped = false

        if (!Settings.canDrawOverlays(this)) {
            ringBuffer.record("PERMISSION_REVOKED", "SYSTEM_ALERT_WINDOW missing on service start")
            repository.setServiceRunningState(false)
            stopSelf()
            return START_NOT_STICKY
        }

        startSidebarForeground()
        repository.setServiceRunningState(true)

        serviceScope.launch {
            val withinSafeRestartBudget = repository.recordServiceStartAndCheckCircuitBreaker()
            if (!withinSafeRestartBudget) {
                ringBuffer.record(
                    "CIRCUIT_BREAKER_TRIPPED",
                    "Service exceeded ${AppRepository.MAX_STARTS_IN_WINDOW} restarts in 60s; aborting overlay"
                )
                postCrashLoopNotification()
                repository.setServiceRunningState(false)
                stopSelf()
                return@launch
            }

            if (overlayWindowManager == null) {
                initializeOverlay()
            }
        }

        return START_STICKY
    }

    private suspend fun initializeOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val pinnedStateFlow = repository.getPinnedShortcuts().stateIn(
            scope = serviceScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

        val repositioningStateFlow = repository.isRepositioningEnabled().stateIn(
            scope = serviceScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PersistedSidebarConfig.DEFAULT_IS_REPOSITIONING_ENABLED
        )

        val initialY = runCatching {
            repository.getHandleYPosition().first()
        }.getOrDefault(PersistedSidebarConfig.DEFAULT_HANDLE_Y_OFFSET_PX)

        val controller = OverlayWindowManager(
            context = this,
            windowManager = wm,
            ringBuffer = ringBuffer,
            pinnedShortcutsFlow = pinnedStateFlow,
            isRepositioningEnabledFlow = repositioningStateFlow,
            onPersistHandleY = { yPx ->
                serviceScope.launch(CoroutineName("SideBar:OverlayPositionStore")) {
                    repository.saveHandleYPosition(yPx)
                }
            },
            onLaunchPackage = { packageName -> launchPinnedApplication(packageName) },
            onOpenMainActivity = { openMainActivity() }
        )

        overlayWindowManager = controller
        controller.attachHandle(initialY)

        serviceScope.launch {
            repository.getHandleYPosition().collect { savedY ->
                overlayWindowManager?.setHandleYFromPersistence(savedY)
            }
        }
    }

    private fun launchPinnedApplication(packageName: String) {
        val startMs = System.currentTimeMillis()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        if (launchIntent == null) {
            ringBuffer.record(
                "LAUNCH_INTENT",
                "target=$packageName result=ACTIVITY_NOT_FOUND latency=0ms"
            )
            Toast.makeText(this, "Unable to launch $packageName", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            startActivity(launchIntent)
            val latencyMs = System.currentTimeMillis() - startMs
            ringBuffer.record(
                "LAUNCH_INTENT",
                "target=$packageName source=drawer_pinned_grid flags=NEW_TASK result=SUCCESS latency=${latencyMs}ms"
            )
        }.onFailure { error ->
            val latencyMs = System.currentTimeMillis() - startMs
            ringBuffer.record(
                "LAUNCH_INTENT",
                "target=$packageName result=${error.javaClass.simpleName} msg=${error.message} latency=${latencyMs}ms"
            )
            Toast.makeText(this, "Could not open app: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            startActivity(intent)
            ringBuffer.record("LAUNCH_MAIN", "Opened MainActivity from drawer")
        }.onFailure { error ->
            ringBuffer.record("LAUNCH_MAIN_ERROR", error.message ?: "Failed to open MainActivity")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayWindowManager?.onConfigurationChanged()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ringBuffer.record("FGS_LIFECYCLE", "onTaskRemoved triggered (app swiped from recents)")
        if (!isExplicitlyStopped && Settings.canDrawOverlays(this)) {
            val restartServiceIntent = Intent(applicationContext, SidebarService::class.java).apply {
                setPackage(packageName)
            }
            val restartPendingIntent = PendingIntent.getForegroundService(
                applicationContext,
                RESTART_REQUEST_CODE,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            try {
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
                ringBuffer.record("FGS_LIFECYCLE", "Scheduled auto-restart alarm via AlarmManager (1000ms)")
            } catch (e: Exception) {
                ringBuffer.record("ALARM_ERROR", "Failed to schedule restart alarm: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        overlayWindowManager?.destroyAll()
        overlayWindowManager = null
        repository.setServiceRunningState(false)
        ringBuffer.record("FGS_LIFECYCLE", "SidebarService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        val pw = writer ?: return
        val uptimeSec = (System.currentTimeMillis() - serviceStartEpochMs) / 1000L
        pw.println("=== SideBar Diagnostic Dump ===")
        pw.println("Service Uptime: ${uptimeSec}s")
        pw.println("Overlay Permission Granted: ${Settings.canDrawOverlays(this)}")
        pw.println("FGS Type: specialUse")
        pw.println()
        pw.println("--- Window & Lifecycle State ---")
        val wmSummary = overlayWindowManager?.buildDiagnosticSummary() ?: "OverlayWindowManager: NOT INITIALIZED"
        pw.print(wmSummary)
        pw.println()
        pw.println("--- Recent Diagnostic Events (Last 50) ---")
        val events = ringBuffer.snapshot()
        if (events.isEmpty()) {
            pw.println("(no events recorded)")
        } else {
            events.forEach { event ->
                pw.println(event.formatForDump())
            }
        }
    }

    private fun startSidebarForeground() {
        val notification = buildOngoingNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_ACTIVE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID_ACTIVE, notification)
            }
            ringBuffer.record("FGS_START", "Started foreground service id=$NOTIFICATION_ID_ACTIVE")
        }.onFailure { error ->
            ringBuffer.record("FGS_START_ERROR", "startForeground failed: ${error.message}")
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildOngoingNotification(): Notification {
        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_active_title))
            .setContentText(getString(R.string.notification_active_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postCrashLoopNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_crash_loop_title))
            .setContentText(getString(R.string.notification_crash_loop_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            nm.notify(NOTIFICATION_ID_CRASH_LOOP, notification)
        }
    }

    private fun inspectPreviousProcessExitReason() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exitList = am.getHistoricalProcessExitReasons(packageName, 0, 1)
                val lastExit = exitList.firstOrNull()
                if (lastExit != null) {
                    ringBuffer.record(
                        "EXIT_INFO",
                        "PrevExit reason=${lastExit.reason} status=${lastExit.status} desc=${lastExit.description}"
                    )
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "sidebar_overlay_channel"
        const val NOTIFICATION_ID_ACTIVE = 1001
        const val NOTIFICATION_ID_CRASH_LOOP = 1002
        const val RESTART_REQUEST_CODE = 401
        const val ACTION_STOP_SERVICE = "com.imtusharahmed.smartsidebar.action.STOP_SERVICE"
    }
}

