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
import com.phonetag.app.ar.ArSupport
import com.phonetag.app.ui.ArGateScreen
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
                // Location and camera (AR tracking is mandatory) are essential; nearby-devices only gates Wi-Fi Direct.
                val essentialGranted = Manifest.permission.ACCESS_FINE_LOCATION !in missing && Manifest.permission.CAMERA !in missing
                LaunchedEffect(essentialGranted) {
                    if (essentialGranted) vm.onPermissionsGranted()
                }
                if (!essentialGranted) {
                    PermissionScreen(onRequest = { launcher.launch(requiredPermissions) })
                    LaunchedEffect(Unit) { launcher.launch(requiredPermissions) }
                } else {
                    val support by vm.ar.support.collectAsStateWithLifecycle()
                    LaunchedEffect(support) { if (support == ArSupport.SUPPORTED) vm.ar.ensureInstalled(this@MainActivity) }
                    when (support) {
                        ArSupport.CHECKING -> ArGateScreen("Checking AR support…", null)
                        ArSupport.UNSUPPORTED -> ArGateScreen(
                            "AR tracking required",
                            "PhoneTag judges every hit with ARCore camera tracking so the game is fair for everyone. " +
                                "This phone doesn't support ARCore (or Google Play Services for AR isn't installed), so it can't join games.",
                        )
                        ArSupport.SUPPORTED -> {
                            val state by vm.state.collectAsStateWithLifecycle()
                            BackHandler(enabled = state.screen != Screen.HOME) { vm.back() }
                            PhoneTagApp(
                                vm = vm,
                                cameraGranted = true,
                                nearbyGranted = Build.VERSION.SDK_INT < 33 || Manifest.permission.NEARBY_WIFI_DEVICES !in missing,
                                onRequestPermissions = { launcher.launch(requiredPermissions) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the Play Store after installing Google Play Services for AR.
        vm.ar.ensureInstalled(this)
    }
}
