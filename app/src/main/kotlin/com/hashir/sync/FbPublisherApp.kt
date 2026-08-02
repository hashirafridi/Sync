package com.hashir.sync

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FbPublisherApp : Application(), Configuration.Provider {

    /**
     * Workers need constructor injection (database, API clients, token store), which the
     * default factory cannot do. Supplying Hilt's factory here is why the manifest
     * removes WorkManager's automatic initializer -- otherwise it would initialise first
     * with the default factory and this would never be consulted.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()
}
