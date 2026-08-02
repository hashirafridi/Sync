package com.hashir.sync.di

import android.content.Context
import androidx.room.Room
import com.hashir.sync.BuildConfig
import com.hashir.sync.api.GraphClient
import com.hashir.sync.api.GraphConfig
import com.hashir.sync.api.auth.AuthApi
import com.hashir.sync.api.pages.PagesApi
import com.hashir.sync.api.publish.PublishApi
import com.hashir.sync.api.publish.VideoPublishApi
import com.hashir.sync.data.local.AccountDao
import com.hashir.sync.data.local.AppDatabase
import com.hashir.sync.data.local.DownloadDao
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.local.PublishJobDao
import com.hashir.sync.downloader.YtDlpMediaFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .build()

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    fun providePageDao(db: AppDatabase): PageDao = db.pageDao()

    @Provides
    fun providePublishJobDao(db: AppDatabase): PublishJobDao = db.publishJobDao()

    @Provides
    fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()
}

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    /**
     * This is the seam that keeps :core-api portable.
     *
     * The Android layer reads credentials from resources and BuildConfig and hands them
     * over as plain strings. :core-api never reaches for BuildConfig itself, so the same
     * classes can be constructed on a server from environment variables instead.
     */
    @Provides
    @Singleton
    fun provideGraphConfig(): GraphConfig = GraphConfig(
        // Deliberately BuildConfig and not R.string.facebook_app_id: that resource carries
        // a placeholder when credentials are missing, so that the Facebook SDK's automatic
        // start-up initialisation does not crash the app. These are the real values, blank
        // when unconfigured, which is what GraphConfig.isConfigured needs to see.
        appId = BuildConfig.FACEBOOK_APP_ID,
        appSecret = BuildConfig.FACEBOOK_APP_SECRET,
    )

    @Provides
    @Singleton
    fun provideGraphClient(config: GraphConfig): GraphClient = GraphClient(config)

    @Provides
    @Singleton
    fun provideAuthApi(client: GraphClient): AuthApi = AuthApi(client)

    @Provides
    @Singleton
    fun providePagesApi(client: GraphClient): PagesApi = PagesApi(client)

    @Provides
    @Singleton
    fun providePublishApi(client: GraphClient): PublishApi = PublishApi(client)

    @Provides
    @Singleton
    fun provideVideoPublishApi(client: GraphClient): VideoPublishApi = VideoPublishApi(client)
}

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {

    /**
     * Exposed as the concrete type rather than the [com.hashir.sync.downloader.MediaFetcher]
     * interface because the settings screen also needs `update()` and `versionName()`,
     * which are specific to the on-device yt-dlp implementation. Anything that only needs
     * to fetch media should still depend on the interface.
     */
    @Provides
    @Singleton
    fun provideMediaFetcher(@ApplicationContext context: Context): YtDlpMediaFetcher =
        YtDlpMediaFetcher(context)
}
