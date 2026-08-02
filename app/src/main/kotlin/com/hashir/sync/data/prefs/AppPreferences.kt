package com.hashir.sync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/**
 * Plain user settings. Kept separate from [com.hashir.sync.data.security.EncryptedTokenStore]
 * so that nothing sensitive shares a file with things that are fine in the clear.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether uploads should wait for Wi-Fi.
     *
     * Off by default: someone who taps Publish generally wants it published, and a post
     * that silently waits for Wi-Fi looks broken. Worth having for anyone on a metered
     * plan, since a Reel can be a couple of hundred megabytes.
     */
    val wifiOnlyUploads: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_WIFI_ONLY] ?: false }

    suspend fun wifiOnlyUploadsNow(): Boolean = wifiOnlyUploads.first()

    suspend fun setWifiOnlyUploads(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_WIFI_ONLY] = enabled }
    }

    private companion object {
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_uploads")
    }
}
