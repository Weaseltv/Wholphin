package com.github.damontecres.wholphin.services.tvprovider

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.kids.KidProtectionStore
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Schedules the [TvProviderWorker] to update the OS resume watching row
 */
@ActivityScoped
class TvProviderSchedulerService
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val workManager: WorkManager,
        private val kidProtectionStore: KidProtectionStore,
    ) {
        private val activity = (context as AppCompatActivity)

        private val supportsTvProvider =
            // TODO <=25 has limited support
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        init {
            // Kid content rules (review round 2): driven by the session epoch, which goes up on every change, so a quick
            // kid -> adult -> same kid can't be conflated away (the session value would look unchanged)
            serverRepository.sessionEpoch.collectLatestIn(activity.lifecycleScope) {
                val user = serverRepository.current.value
                workManager.cancelUniqueWork(TvProviderWorker.WORK_NAME)
                if (supportsTvProvider) {
                    activity.lifecycleScope.launchIO(ExceptionHandler()) {
                        if (BuildConfig.KID_PROFILES_ENABLED) {
                            // Kid content rules (contract section 10, review round 1): the previous profile's launcher
                            // rows never stay up for a kid or unknown session, nor on a protected TV's chooser (no
                            // session); the worker refills them for the next profile. Any other change still stops
                            // runs started for the previous session
                            val clear = if (user == null) kidProtectionStore.isProtected() else user.user.profileKind != ProfileKind.ADULT
                            if (clear) LauncherRows.clear(context) else LauncherRows.invalidate()
                        }
                        if (user != null) {
                            // Schedule a new worker whenever the user changes
                            Timber.i("Scheduling TvProviderWorker for ${user.user}")
                            workManager
                                .enqueueUniquePeriodicWork(
                                    uniqueWorkName = TvProviderWorker.WORK_NAME,
                                    existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                                    request =
                                        PeriodicWorkRequestBuilder<TvProviderWorker>(
                                            repeatInterval = 1.hours.toJavaDuration(),
                                        ).setBackoffCriteria(
                                            BackoffPolicy.LINEAR,
                                            15.minutes.toJavaDuration(),
                                        ).setInputData(
                                            workDataOf(
                                                TvProviderWorker.PARAM_USER_ID to user.user.id.toString(),
                                                TvProviderWorker.PARAM_SERVER_ID to user.server.id.toString(),
                                            ),
                                        ).setInitialDelay(60.seconds.toJavaDuration())
                                            .build(),
                                ).await()
                        }
                    }
                }
            }
        }

        /**
         * Run the [TvProviderWorker] as a one-off instead of scheduled
         */
        fun launchOneTimeRefresh() {
            if (supportsTvProvider) {
                activity.lifecycleScope.launchIO(ExceptionHandler()) {
                    serverRepository.current.value?.let { user ->
                        Timber.i("Scheduling on-time TvProviderWorker for ${user.user}")
                        workManager.enqueue(
                            OneTimeWorkRequestBuilder<TvProviderWorker>()
                                .setInputData(
                                    workDataOf(
                                        TvProviderWorker.PARAM_USER_ID to user.user.id.toString(),
                                        TvProviderWorker.PARAM_SERVER_ID to user.server.id.toString(),
                                    ),
                                ).build(),
                        )
                    }
                }
            }
        }
    }
