package io.github.priencelucifer.michisonae

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal fun shouldRetrySync(
    outcome: UploadOutcome?,
    hasMorePending: Boolean,
): Boolean = when (outcome) {
    UploadOutcome.ACCEPTED -> hasMorePending
    UploadOutcome.AUTH_EXPIRED, UploadOutcome.RETRY -> true
    UploadOutcome.REJECTED, null -> false
}

internal enum class LatestSyncStatus {
    NEVER,
    SUCCEEDED,
    RETRYING,
    REJECTED,
    PAUSED,
}

internal fun latestSyncStatus(outcome: UploadOutcome?): LatestSyncStatus = when (outcome) {
    UploadOutcome.ACCEPTED, null -> LatestSyncStatus.SUCCEEDED
    UploadOutcome.AUTH_EXPIRED, UploadOutcome.RETRY -> LatestSyncStatus.RETRYING
    UploadOutcome.REJECTED -> LatestSyncStatus.REJECTED
}

internal class ObservationSyncStatus(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun latest(): LatestSyncStatus = runCatching {
        LatestSyncStatus.valueOf(
            preferences.getString(STATUS, null) ?: LatestSyncStatus.NEVER.name,
        )
    }.getOrDefault(LatestSyncStatus.NEVER)

    fun record(status: LatestSyncStatus) {
        check(preferences.edit().putString(STATUS, status.name).commit()) {
            "Latest sync status could not be stored"
        }
    }

    fun clear() {
        preferences.edit().remove(STATUS).apply()
    }

    private companion object {
        const val PREFERENCES = "michisonae-observation-sync-status"
        const val STATUS = "latest"
    }
}

internal object ObservationSyncScheduler {
    private const val JOB_ID = 0x4D53
    private const val ENDPOINT_PREFERENCES = "michisonae-backend-endpoint"
    private const val ENDPOINT = "base-url"

    fun schedule(context: Context, baseUrl: String): Boolean {
        val safeBaseUrl = validatedApiBaseUrl(baseUrl)
        val appContext = context.applicationContext
        check(
            appContext.getSharedPreferences(ENDPOINT_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(ENDPOINT, safeBaseUrl)
                .commit(),
        ) {
            "Backend endpoint could not be stored"
        }
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(appContext, ObservationUploadJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(
                30_000L,
                JobInfo.BACKOFF_POLICY_EXPONENTIAL,
            )
            .build()
        return appContext.getSystemService(JobScheduler::class.java).schedule(job) ==
            JobScheduler.RESULT_SUCCESS
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        context.getSharedPreferences(ENDPOINT_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(ENDPOINT)
            .apply()
        ObservationSyncStatus(context).clear()
    }

    fun configuredBaseUrl(context: Context): String? =
        context.getSharedPreferences(ENDPOINT_PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENDPOINT, null)
            ?.let { runCatching { validatedApiBaseUrl(it) }.getOrNull() }
}

internal class ObservationUploadJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running: Future<*>? = null
    @Volatile
    private var invocationGeneration = 0

    override fun onStartJob(params: JobParameters): Boolean {
        val baseUrl = ObservationSyncScheduler.configuredBaseUrl(this) ?: return false
        val invocation = ++invocationGeneration
        running = executor.submit {
            val queue = OfflineObservationQueue(this)
            val status = ObservationSyncStatus(this)
            var latestStatus = LatestSyncStatus.RETRYING
            val retry = try {
                val outcome = AnonymousCredentialManager(
                    api = MichiSonaeApi(baseUrl),
                    store = EncryptedCredentialStore(this),
                    credentialCreationAllowed = {
                        AppPreferences(this).hasAcceptedPrivacy()
                    },
                ).uploadPending(queue)
                latestStatus = latestSyncStatus(outcome)
                shouldRetrySync(outcome, queue.pendingCount() > 0)
            } catch (_: CredentialCreationDisabled) {
                latestStatus = LatestSyncStatus.PAUSED
                false
            } catch (_: Exception) {
                true
            }
            if (invocationGeneration == invocation) {
                runCatching { status.record(latestStatus) }
                jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        invocationGeneration += 1
        running?.cancel(true)
        running = null
        return true
    }

    override fun onDestroy() {
        invocationGeneration += 1
        running?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
