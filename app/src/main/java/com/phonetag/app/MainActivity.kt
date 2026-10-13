package com.phonetag.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.ui.PermissionScreen
import com.phonetag.app.ui.PhoneTagApp

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    private fun missingPermissions() = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Players hold the phone up during a match; never let the screen (and the host's server) sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF3D3D), secondary = Color(0xFF3D8BFF))) {
                var missing by remember { mutableStateOf(missingPermissions()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    missing = missingPermissions()
                }
                // Location is essential; camera and nearby-devices only gate their own features.
                val essentialGranted = Manifest.permission.ACCESS_FINE_LOCATION !in missing
                LaunchedEffect(essentialGranted) {
                    if (essentialGranted) vm.onPermissionsGranted()
                }
                if (!essentialGranted) {
                    PermissionScreen(onRequest = { launcher.launch(requiredPermissions) })
                    LaunchedEffect(Unit) { launcher.launch(requiredPermissions) }
                } else {
                    val state by vm.state.collectAsStateWithLifecycle()
                    BackHandler(enabled = state.screen != Screen.HOME) { vm.back() }
                    PhoneTagApp(
                        vm = vm,
                        cameraGranted = Manifest.permission.CAMERA !in missing,
                        nearbyGranted = Build.VERSION.SDK_INT < 33 || Manifest.permission.NEARBY_WIFI_DEVICES !in missing,
                        onRequestPermissions = { launcher.launch(requiredPermissions) },
                    )
                }
            }
        }
    }
}
