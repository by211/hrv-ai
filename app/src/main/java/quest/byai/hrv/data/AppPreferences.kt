package quest.byai.hrv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "resonance_settings")

data class UserSettings(
    val onboardingComplete: Boolean = false,
    val savedDeviceId: String? = null,
    val preferredRate: Double = 6.0,
    val soundEnabled: Boolean = false,
    val soundStyle: BreathingSoundStyle = BreathingSoundStyle.RELAXED_BREATHING,
    val hapticsEnabled: Boolean = true,
)

enum class BreathingSoundStyle(val displayName: String) {
    RELAXED_BREATHING("Relaxed breathing"),
    OCEAN_SWELL("Ocean swell"),
    SINGING_BOWLS("Singing bowls"),

    ;

    companion object {
        fun fromStoredName(storedName: String?): BreathingSoundStyle =
            entries.find { it.name == storedName } ?: RELAXED_BREATHING
    }
}

class AppPreferences(private val context: Context) {
    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val savedDeviceId = stringPreferencesKey("saved_device_id")
        val preferredRate = doublePreferencesKey("preferred_rate")
        val soundEnabled = booleanPreferencesKey("sound_enabled")
        val soundStyle = stringPreferencesKey("sound_style")
        val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { values ->
        UserSettings(
            onboardingComplete = values[Keys.onboardingComplete] ?: false,
            savedDeviceId = values[Keys.savedDeviceId],
            preferredRate = values[Keys.preferredRate] ?: 6.0,
            soundEnabled = values[Keys.soundEnabled] ?: false,
            soundStyle = BreathingSoundStyle.fromStoredName(values[Keys.soundStyle]),
            hapticsEnabled = values[Keys.hapticsEnabled] ?: true,
        )
    }

    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboardingComplete] = true }
    suspend fun saveDevice(deviceId: String) = context.dataStore.edit { it[Keys.savedDeviceId] = deviceId }
    suspend fun clearDevice() = context.dataStore.edit { it.remove(Keys.savedDeviceId) }
    suspend fun savePreferredRate(rate: Double) = context.dataStore.edit { it[Keys.preferredRate] = rate }
    suspend fun setSoundEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.soundEnabled] = enabled }
    suspend fun setSoundStyle(style: BreathingSoundStyle) = context.dataStore.edit { it[Keys.soundStyle] = style.name }
    suspend fun setHapticsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.hapticsEnabled] = enabled }
}
