package com.mhss.app.shade.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mhss.app.shade.detection.DEFAULT_CONFIDENCE_PERCENT
import com.mhss.app.shade.detection.DEFAULT_DOWNSAMPLE_FACTOR
import com.mhss.app.shade.detection.MAX_DOWNSAMPLE_FACTOR
import com.mhss.app.shade.detection.MIN_DOWNSAMPLE_FACTOR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shade_settings")

@Factory
class PreferenceManager(private val context: Context) {

    companion object {
        private val CONFIDENCE_PERCENT = floatPreferencesKey("confidence_percent")
        private val PERFORMANCE_MODE = booleanPreferencesKey("power_mode_enabled")
        private val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        private val FULL_SCREEN_MODE = booleanPreferencesKey("full_screen_mode_enabled")
        private val HAS_SHOWN_INITIAL_DIALOG = booleanPreferencesKey("has_shown_unsupported_dialog")
        private val AUTO_START_APPS = stringSetPreferencesKey("auto_start_apps")
        private val PIXELATION_LEVEL = intPreferencesKey("pixelation_level")
    }

    val confidencePercentFlow: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[CONFIDENCE_PERCENT] ?: DEFAULT_CONFIDENCE_PERCENT
        }

    val performanceModeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PERFORMANCE_MODE] ?: false
        }

    val overlayOpacityFlow: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[OVERLAY_OPACITY] ?: 100f
        }

    val fullScreenModeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[FULL_SCREEN_MODE] ?: false
        }

    val autoStartAppsFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_START_APPS] ?: emptySet()
        }

    val pixelationLevelFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PIXELATION_LEVEL] ?: DEFAULT_DOWNSAMPLE_FACTOR
        }

    suspend fun saveConfidencePercent(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[CONFIDENCE_PERCENT] = value
        }
    }

    suspend fun getConfidencePercent(): Float {
        return context.dataStore.data
            .map { preferences -> preferences[CONFIDENCE_PERCENT] ?: DEFAULT_CONFIDENCE_PERCENT }
            .first()
    }

    suspend fun isPerformanceModeEnabled(): Boolean {
        return context.dataStore.data
            .map { preferences -> preferences[PERFORMANCE_MODE] ?: false }
            .first()
    }

    suspend fun setPowerMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PERFORMANCE_MODE] = enabled
        }
    }

    suspend fun getOverlayOpacity(): Float {
        return context.dataStore.data
            .map { preferences -> preferences[OVERLAY_OPACITY] ?: 100f }
            .first()
    }

    suspend fun setOverlayOpacity(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[OVERLAY_OPACITY] = value.coerceIn(0f, 100f)
        }
    }

    suspend fun isFullScreenModeEnabled(): Boolean {
        return context.dataStore.data
            .map { preferences -> preferences[FULL_SCREEN_MODE] ?: false }
            .first()
    }

    suspend fun setFullScreenMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FULL_SCREEN_MODE] = enabled
        }
    }

    suspend fun setHasShownInitialDialog(shown: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_SHOWN_INITIAL_DIALOG] = shown
        }
    }

    suspend fun hasShownInitialDialog(): Boolean {
        return context.dataStore.data
            .map { preferences -> preferences[HAS_SHOWN_INITIAL_DIALOG] ?: false }
            .first()
    }

    suspend fun setAutoStartApps(apps: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_START_APPS] = apps
        }
    }

    suspend fun getPixelationLevel(): Int {
        return context.dataStore.data
            .map { preferences -> preferences[PIXELATION_LEVEL] ?: DEFAULT_DOWNSAMPLE_FACTOR }
            .first()
    }

    suspend fun setPixelationLevel(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[PIXELATION_LEVEL] = value.coerceIn(
                MIN_DOWNSAMPLE_FACTOR,
                MAX_DOWNSAMPLE_FACTOR
            )
        }
    }
}