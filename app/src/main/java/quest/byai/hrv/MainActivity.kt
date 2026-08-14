package quest.byai.hrv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import quest.byai.hrv.ui.MainViewModel
import quest.byai.hrv.ui.MainViewModelFactory
import quest.byai.hrv.ui.ResonanceApp
import quest.byai.hrv.ui.theme.ResonanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ResonanceTheme {
                val application = application as ResonanceApplication
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModelFactory(application.container),
                )
                ResonanceApp(viewModel)
            }
        }
    }
}
