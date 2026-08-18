package quest.byai.hrv

import android.app.Application
import androidx.room.Room
import quest.byai.hrv.data.AppPreferences
import quest.byai.hrv.data.ResonanceDatabase
import quest.byai.hrv.data.SessionRepository
import quest.byai.hrv.sensor.HeartRateSensor
import quest.byai.hrv.sensor.StandardBleHeartRateSensor

class ResonanceApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = Room.databaseBuilder(
        application,
        ResonanceDatabase::class.java,
        "resonance.db",
    ).build()

    val preferences = AppPreferences(application)
    val repository = SessionRepository(database.resonanceDao())
    val sensor: HeartRateSensor = StandardBleHeartRateSensor(application)
}
