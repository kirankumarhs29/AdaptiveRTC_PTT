package com.netsense.meshapp

import android.Manifest
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import net.sense.mesh.AndroidMeshService
import net.sense.mesh.MeshViewModel
import net.sense.mesh.MeshHomeScreen
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.netsense.mesh.AppLogger
import com.netsense.mesh.AppLogger.Module
import com.netsense.mesh.EcsBridge
import com.netsense.mesh.LogExporter

class MainActivity : ComponentActivity() {
    companion object {
        private const val VOICE_PERMISSION_REQUEST = 1010
    }

    private lateinit var viewModel: MeshViewModel
    private lateinit var meshService: AndroidMeshService
    private lateinit var meshManager: com.netsense.mesh.MeshManager
    private lateinit var voiceManager: com.netsense.mesh.WifiDirectVoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)

        // Init loggers as early as possible so every app event is captured.
        AppLogger.init(this)
        EcsBridge.setLogPath("${filesDir.absolutePath}/core.log")
        AppLogger.info(Module.SYSTEM, "MainActivity onCreate")
        AppLogger.info(Module.SYSTEM, "ecs library loaded available=${EcsBridge.available}")

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val nodeSuffix = (androidId ?: "unknown").takeLast(6)
        val localNodeId = "node-$nodeSuffix"

        meshManager = com.netsense.mesh.MeshManager(this, localNodeId)
        voiceManager = com.netsense.mesh.WifiDirectVoiceManager(this, localNodeId)
        meshService = AndroidMeshService(this, meshManager, voiceManager)
        viewModel = MeshViewModel(meshService)

        ensureVoicePermissions()

        setContent {
            val colors = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF5EEAD4),
                secondary = androidx.compose.ui.graphics.Color(0xFF93C5FD),
                background = androidx.compose.ui.graphics.Color(0xFF070A0F),
                surface = androidx.compose.ui.graphics.Color(0xFF111827),
                surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1F2937)
            )
            MaterialTheme(colorScheme = colors) {
                Surface {
                    MeshHomeScreen(
                        viewModel = viewModel,
                        onExportLogs = {
                            val result = LogExporter.export(this@MainActivity)
                            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }

        viewModel.start(localNodeId)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (!meshManager.onRequestPermissionsResult(requestCode, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun ensureVoicePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), VOICE_PERMISSION_REQUEST)
        }
    }
}
