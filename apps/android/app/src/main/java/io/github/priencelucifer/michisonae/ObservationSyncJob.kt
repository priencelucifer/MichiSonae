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
                JobInfo.MIN_BACKOFF_MILLIS,
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
    }

    fun configuredBaseUrl(context: Context): String? =
        context.getSharedPreferences(ENDPOINT_PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENDPOINT, null)
            ?.let { runCatching { validatedApiBaseUrl(it) }.getOrNull() }
}

internal class ObservationUploadJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val baseUrl = ObservationSyncScheduler.configuredBaseUrl(this) ?: return false
        running = executor.submit {
            val queue = OfflineObservationQueue(this)
            val retry = runCatching {
                val outcome = AnonymousCredentialManager(
                    api = MichiSonaeApi(baseUrl),
                    store = EncryptedCredentialStore(this),
                ).uploadPending(queue)
                shouldRetrySync(outcome, queue.pendingCount() > 0)
            }.getOrDefault(true)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel(true)
        running = null
        return true
    }

    override fun onDestroy() {
        running?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
