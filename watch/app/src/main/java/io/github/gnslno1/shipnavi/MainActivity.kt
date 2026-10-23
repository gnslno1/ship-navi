package io.github.gnslno1.shipnavi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var hub: SensorHub
    private var keepScreenOn by mutableStateOf(true)

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hub.startLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = SensorHub(applicationContext)
        hub.start()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            hub.startLocation()
        } else {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        applyKeepScreenOn()

        setContent {
            TestScreen(
                r = hub.readings,
                keepScreenOn = keepScreenOn,
                onToggleScreen = { keepScreenOn = !keepScreenOn; applyKeepScreenOn() },
            )
        }
    }

    // 시험 단계에서는 화면이 켜져 있어야 GPS가 계속 측정된다 (백그라운드 측정은 2단계에서).
    private fun applyKeepScreenOn() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        hub.flush()
    }

    override fun onDestroy() {
        hub.stop()
        super.onDestroy()
    }
}
