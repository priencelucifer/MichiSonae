package io.github.priencelucifer.michisonae

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

internal data class BootRecoveryPlan(
    val rescheduleUploads: Boolean,
    val promptMonitoringRestart: Boolean,
)

internal fun bootRecoveryPlan(
    privacyAccepted: Boolean,
    endpointConfigured: Boolean,
    monitoringShouldResume: Boolean,
): BootRecoveryPlan = if (!privacyAccepted) {
    BootRecoveryPlan(false, false)
} else {
    BootRecoveryPlan(
        rescheduleUploads = endpointConfigured,
        promptMonitoringRestart = monitoringShouldResume,
    )
}

internal class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (DataLifecycleGate.isDeletionInProgress(context)) return

        val plan = CredentialOperationCoordinator.runExclusive {
            val preferences = AppPreferences(context)
            val baseUrl = ObservationSyncScheduler.configuredBaseUrl(context)
            bootRecoveryPlan(
                privacyAccepted = preferences.hasAcceptedPrivacy(),
                endpointConfigured = baseUrl != null,
                monitoringShouldResume = preferences.shouldResumeMonitoring(),
            ).also {
                if (it.rescheduleUploads) {
                    runCatching {
                        ObservationSyncScheduler.schedule(context, checkNotNull(baseUrl))
                    }
                }
            }
        }
        if (plan.promptMonitoringRestart) {
            showMonitoringRecoveryNotification(context)
        }
    }
}

private fun showMonitoringRecoveryNotification(context: Context) {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL,
                "Monitoring recovery",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminds you to resume road monitoring after a phone restart"
            },
        )
    }

    val openApp = PendingIntent.getActivity(
        context,
        RECOVERY_NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = android.app.Notification.Builder(context, RECOVERY_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Resume road monitoring")
        .setContentText("Open MichiSonae to resume warnings after the phone restart.")
        .setContentIntent(openApp)
        .setAutoCancel(true)
        .build()
    runCatching { notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification) }
}

private const val RECOVERY_CHANNEL = "michisonae-monitoring-recovery"
private const val RECOVERY_NOTIFICATION_ID = 0x4D54
