package com.phonetag.app.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.GameViewModel
import com.phonetag.app.UiState
import com.phonetag.app.ar.ArCameraView
import com.phonetag.app.ar.ArSupport
import com.phonetag.app.ar.SyncMarkers

/**
 * Syncs this phone into the lobby's shared AR arena.
 *
 * Not synced: point the camera at the marker on a synced player's screen; ARCore measures it
 * and, together with that player's broadcast screen pose, works out the shared frame.
 * Synced: show your own marker so others can sync from you.
 */
@Composable
fun ArenaSyncScreen(vm: GameViewModel, state: UiState, cameraGranted: Boolean) {
    val support by vm.ar.support.collectAsStateWithLifecycle()
    val sync by vm.ar.sync.collectAsStateWithLifecycle()
    val lobby = state.lobby
    val arena = lobby?.arena
    val canRun = support == ArSupport.SUPPORTED && cameraGranted

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (canRun) ArCameraView(vm.ar, Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xCC101418), RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AR arena sync", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::closeArenaSync) { Text("Done") }
                }
                val reference = arena?.referenceId?.takeIf { it != 0 }?.let(state::nameOf)
                val beaconNames = state.snapshot?.players.orEmpty().filter { it.beacon != null && it.id != state.myId }.map { state.nameOf(it.id) }
                val (headline, detail) = when {
                    support == ArSupport.CHECKING -> "Checking AR support…" to ""
                    support == ArSupport.UNSUPPORTED -> "This phone can't do AR tracking" to
                        "You'll still play with GPS, compass and camera person detection."
                    !cameraGranted -> "Camera permission needed" to "Allow camera access to use AR tracking."
                    arena == null || arena.epoch == 0 -> "No arena yet" to "It starts as soon as an AR-capable phone joins."
                    !sync.tracking -> "Starting tracking…" to "Move the phone slowly so it can map the room."
                    sync.isReference -> "You define the arena ✓" to "Show your marker so others can sync from you."
                    sync.synced -> "Synced ✓" to "Show your marker so others can sync from you" + (reference?.let { ". Arena reference: $it." } ?: ".")
                    sync.scanningMarkerOf != null -> "Scanning ${state.nameOf(sync.scanningMarkerOf!!)}'s marker…" to "Hold both phones still."
                    beaconNames.isNotEmpty() -> "Scan a marker" to "Point your camera at ${beaconNames.joinToString()}'s screen from 20–40 cm away."
                    else -> "Waiting for a marker" to "Ask a synced player${reference?.let { " (e.g. $it)" } ?: ""} to open AR sync and tap \"Show my marker\"."
                }
                Text(headline, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (detail.isNotEmpty()) Text(detail, color = Color.LightGray, fontSize = 14.sp)
                if (sync.scanningMarkerOf != null) {
                    LinearProgressIndicator(progress = { sync.samples / 15f }, modifier = Modifier.fillMaxWidth())
                }
                if (sync.synced) {
                    Text("Tip: you can stay on this screen; the match view takes over when the host starts.", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            if (sync.synced) {
                Button(onClick = vm::toggleMarker, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.showingMarker) "Hide my marker" else "Show my marker")
                }
            }
            OutlinedButton(onClick = vm::closeArenaSync, modifier = Modifier.fillMaxWidth()) { Text("Back to lobby", color = Color.White) }
        }

        if (state.showingMarker && sync.synced) {
            MarkerCard(slot = SyncMarkers.slotFor(state.myId), steady = sync.steady, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** The sync marker, drawn at its exact physical size so ARCore can measure its distance. */
@Composable
private fun MarkerCard(slot: Int, steady: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bitmap = remember(slot) { SyncMarkers.bitmap(slot).asImageBitmap() }
    val markerPx = SyncMarkers.WIDTH_M / 0.0254f * context.resources.displayMetrics.xdpi
    val markerDp = with(density) { markerPx.toDp() }

    // Full brightness makes the marker much easier to detect.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val old = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose { if (old != null) window.attributes = window.attributes.apply { screenBrightness = old } }
    }

    Column(
        modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(bitmap, contentDescription = "Sync marker", filterQuality = FilterQuality.None, modifier = Modifier.size(markerDp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (steady) "Ready: let them scan from 20–40 cm" else "Hold still…",
            color = if (steady) Color(0xFF2E7D32) else Color(0xFFE65100),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}
