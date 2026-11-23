package com.metrocast.app.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Updater
import java.util.concurrent.TimeUnit

/**
 * Updates copy only: once a day, around 1 AM and only on Wi-Fi, asks your PC if there's a newer
 * version. Android may run it a little later to save battery; it never runs more than once a day.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val retry = inputData.getBoolean(RETRY, false)
        val repo = Repository(applicationContext)
        runCatching { repo.dailyUpdateCheck(retry = retry) }
        // PC didn't answer: one more try in about 30 minutes (then the day is done either way).
        if (!retry && repo.pcDidntAnswer) runCatching {
            val again = androidx.work.OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInitialDelay(30, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(RETRY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("$NAME-retry", androidx.work.ExistingWorkPolicy.REPLACE, again)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "metrocast-update-check"
        const val RETRY = "retry"

        /** Starts (or stops) the nightly check, depending on whether an address is set. */
        fun sync(context: Context) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                if (!Updater.enabled || com.metrocast.app.data.Prefs(context).updateAddress.isBlank()) {
                    wm.cancelUniqueWork(NAME); return
                }
                val delay = Updater.untilOneAm(java.time.LocalDateTime.now())
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
