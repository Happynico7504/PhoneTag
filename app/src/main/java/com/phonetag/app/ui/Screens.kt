package com.phonetag.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.GameViewModel
import com.phonetag.app.Screen
import com.phonetag.app.Transport
import com.phonetag.app.ar.ArCameraView
import com.phonetag.app.ar.ArSupport
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import com.phonetag.app.UiState
import com.phonetag.core.GameMode
import com.phonetag.core.Phase
import com.phonetag.core.Protocol
import com.phonetag.core.VoiceRouter
import com.phonetag.core.ServerMessage
import com.phonetag.core.Team

val RedTeam = Color(0xFFFF4D4D)
val BlueTeam = Color(0xFF4D9BFF)
fun Team.color() = when (this) {
    Team.RED -> RedTeam
    Team.BLUE -> BlueTeam
    Team.NONE -> Color(0xFFBDBDBD)
}

@Composable
fun PhoneTagApp(vm: GameViewModel, cameraGranted: Boolean, nearbyGranted: Boolean, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.screen) {
            Screen.HOME -> HomeScreen(vm, state, nearbyGranted, onRequestPermissions)
            Screen.NEARBY -> NearbyScreen(vm)
            Screen.ONLINE -> OnlineScreen(vm, state)
            Screen.CONNECTING -> ConnectingScreen(state.status ?: "Connecting…", onCancel = vm::leave)
            Screen.SESSION -> when {
                state.lobby?.phase == Phase.RUNNING -> GameScreen(vm, state, cameraGranted)
                state.showArenaSync -> ArenaSyncScreen(vm, state, cameraGranted)
                state.results != null && state.lobby?.phase == Phase.ENDED -> ResultsScreen(vm, state, state.results!!)
                else -> LobbyScreen(vm, state, cameraGranted)
            }
        }
        // Errors during a running match are shown inside the HUD instead of as a dialog.
        val err = state.error
        if (err != null && state.lobby?.phase != Phase.RUNNING) {
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                text = { Text(err) },
            )
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PhoneTag", fontSize = 40.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "PhoneTag tracks every player with AR camera tracking, backed up by GPS, so you can tag each other. " +
                "It needs the camera, location and nearby Wi-Fi devices (for Wi-Fi Direct).",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant permissions") }
    }
}

/** Shown instead of the app while AR support is being checked, or if the phone can't do AR. */
@Composable
fun ArGateScreen(title: String, detail: String?) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PhoneTag", fontSize = 40.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        if (detail == null) CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, textAlign = TextAlign.Center, color = Color.LightGray)
        }
    }
}

@Composable
private fun HomeScreen(vm: GameViewModel, state: UiState, nearbyGranted: Boolean, onRequestPermissions: () -> Unit) {
    val wifiOn by vm.wifiDirect.enabled.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("PhoneTag", fontSize = 44.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("Laser tag with your phones", color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.playerName,
            onValueChange = vm::setName,
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Nearby (Wi-Fi Direct, no internet needed)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        val wifiReady = nearbyGranted && vm.wifiDirect.isSupported && wifiOn
        Button(onClick = vm::hostNearby, enabled = wifiReady && state.playerName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Host a game")
        }
        OutlinedButton(onClick = vm::openNearby, enabled = wifiReady, modifier = Modifier.fillMaxWidth()) {
            Text("Join a nearby game")
        }
        when {
            !vm.wifiDirect.isSupported -> Hint("This phone doesn't support Wi-Fi Direct. Use online play.")
            !nearbyGranted -> TextButton(onClick = onRequestPermissions) { Text("Allow \"Nearby devices\" to use Wi-Fi Direct") }
            !wifiOn -> Hint("Turn on Wi-Fi to use Wi-Fi Direct.")
        }
        Spacer(Modifier.height(8.dp))
        Text("Too far apart? Use a dedicated server", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = vm::openOnline, modifier = Modifier.fillMaxWidth()) { Text("Play online") }
        Spacer(Modifier.weight(1f))
        Hint("Lobbies hold up to ${Protocol.MAX_PLAYERS_PER_LOBBY} players. Everyone plays with AR camera tracking, so hits are judged the same way for all.")
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)

@Composable
private fun NearbyScreen(vm: GameViewModel) {
    val games by vm.nearbyGames.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::back) { Text("Back") }
            Text("Nearby games", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::refreshNearby) { Text("Rescan") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Searching via Wi-Fi Direct… ask the host to keep the app open.", color = Color.Gray, fontSize = 13.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games, key = { it.deviceAddress }) { game ->
                Card(Modifier.fillMaxWidth().clickable { vm.joinNearby(game) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(game.lobbyName ?: game.deviceName, fontWeight = FontWeight.Bold)
                        Text(
                            if (game.lobbyName != null) "PhoneTag game on ${game.deviceName}" else "Wi-Fi Direct device (not advertising a game yet)",
                            color = Color.Gray, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineScreen(vm: GameViewModel, state: UiState) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::back) { Text("Back") }
            Text("Online play", style = MaterialTheme.typography.titleLarge)
        }
        // Server choice: the official instance (one tap) or your own, kept in a separate field.
        val official = state.useOfficialServer
        val serverReady = official || state.serverAddress.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerChoice(
                title = "Official server",
                detail = GameViewModel.OFFICIAL_SERVER.substringBefore(':'),
                selected = official,
                onClick = { vm.useOfficialServer(true) },
                modifier = Modifier.weight(1f),
            )
            ServerChoice(
                title = "Custom server",
                detail = state.serverAddress.ifBlank { "your own host" },
                selected = !official,
                onClick = { vm.useOfficialServer(false) },
                modifier = Modifier.weight(1f),
            )
        }
        if (!official) {
            OutlinedTextField(
                value = state.serverAddress,
                onValueChange = vm::setServerAddress,
                label = { Text("Custom server (host:port)") },
                placeholder = { Text("play.example.com:${Protocol.DEFAULT_PORT}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(onClick = vm::createOnline, enabled = serverReady, modifier = Modifier.fillMaxWidth()) {
            Text("Create new lobby")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(5) },
                label = { Text("Lobby code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.joinOnline(code) }, enabled = code.length == 5 && serverReady) { Text("Join") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Open lobbies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::refreshOnlineLobbies, enabled = serverReady) { Text("Refresh") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.onlineLobbies, key = { it.code }) { l ->
                val full = l.players >= l.maxPlayers
                Card(Modifier.fillMaxWidth().clickable(enabled = !full) { vm.joinOnline(l.code) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, fontWeight = FontWeight.Bold)
                            Text(l.code, fontFamily = FontFamily.Monospace, color = Color.Gray)
                        }
                        Text("${l.players}/${l.maxPlayers}", color = if (full) RedTeam else Color.Unspecified)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF)
    Column(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ConnectingScreen(status: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(status, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun LobbyScreen(vm: GameViewModel, state: UiState, cameraGranted: Boolean) {
    val lobby = state.lobby ?: return ConnectingScreen("Joining lobby…", vm::leave)
    val group by vm.groupInfo.collectAsStateWithLifecycle()
    val settings = lobby.settings
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lobby.name, style = MaterialTheme.typography.titleLarge)
                if (state.transport == Transport.ONLINE) {
                    Text("Code: ${lobby.code}", fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Wi-Fi Direct", color = Color.Gray)
                }
            }
            Text("${lobby.players.size}/${settings.maxPlayers}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (state.transport == Transport.WIFI_DIRECT_HOST) {
            group?.let {
                Hint("Others tap \"Join a nearby game\". Phones without Wi-Fi Direct can connect to Wi-Fi \"${it.ssid}\" (password ${it.passphrase}), then Play online → server 192.168.49.1 → Create new lobby.")
            }
        }

        if (settings.mode == GameMode.TEAMS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setTeam(Team.RED) }, modifier = Modifier.weight(1f)) { Text("Join Red") }
                Button(onClick = { vm.setTeam(Team.BLUE) }, modifier = Modifier.weight(1f)) { Text("Join Blue") }
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lobby.players, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(if (settings.mode == GameMode.TEAMS) p.team.color() else Color.Gray, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(p.name + if (p.id == state.myId) " (you)" else "", modifier = Modifier.weight(1f))
                    // Everyone must be synced to the shared arena before the match can start.
                    val (arTag, arColor) = when {
                        p.id == lobby.arena.referenceId && state.isSynced(p.id) -> "AR ref" to Color(0xFF69F0AE)
                        state.isSynced(p.id) -> "AR synced" to Color(0xFF69F0AE)
                        else -> "not synced" to Color(0xFFFFC107)
                    }
                    Text(arTag, color = arColor, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                    if (p.isHost) Text("HOST", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            }
        }

        ArenaRow(vm, state, cameraGranted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice radio", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Talks to ${if (settings.mode == GameMode.TEAMS) "teammates" else "players"} more than ${VoiceRouter.RANGE_M.toInt()} m away; " +
                        "muted up close to avoid feedback. Headphones work best.",
                    color = Color.Gray, fontSize = 13.sp,
                )
            }
            MicButton(state, vm::toggleMic)
        }

        if (state.isHost) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            ChoiceRow("Mode", listOf(GameMode.FREE_FOR_ALL to "Free for all", GameMode.TEAMS to "Teams"), settings.mode) {
                vm.updateSettings(settings.copy(mode = it))
            }
            ChoiceRow("Time", listOf(300 to "5 min", 600 to "10 min", 900 to "15 min"), settings.matchDurationSec) {
                vm.updateSettings(settings.copy(matchDurationSec = it))
            }
            ChoiceRow("Range", listOf(30.0 to "30 m", 60.0 to "60 m", 100.0 to "100 m"), settings.maxRangeM) {
                vm.updateSettings(settings.copy(maxRangeM = it))
            }
            val unsynced = lobby.players.count { !state.isSynced(it.id) }
            Button(onClick = vm::startMatch, enabled = lobby.players.size >= 2 && unsynced == 0, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        lobby.players.size < 2 -> "Waiting for players…"
                        unsynced > 0 -> "Waiting for $unsynced to sync AR…"
                        else -> "Start match"
                    }
                )
            }
        } else {
            Hint("Waiting for the host to start. ${settings.mode.label()}, ${settings.matchDurationSec / 60} min, ${settings.maxRangeM.toInt()} m range.")
        }
        OutlinedButton(onClick = vm::leave, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
    }
}

/**
 * Live AR camera strip plus this phone's sync status. The camera keeps running in the lobby so
 * the phone stays tracked (and synced) right up to the start of the match. The host also picks
 * the arena's reference phone here.
 */
@Composable
private fun ArenaRow(vm: GameViewModel, state: UiState, cameraGranted: Boolean) {
    val lobby = state.lobby ?: return
    val sync by vm.ar.sync.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            if (cameraGranted) ArCameraView(vm.ar, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("AR tracking (required)", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !sync.tracking -> "Starting: move the phone slowly"
                    sync.isReference -> "Synced ✓ (this phone defines the arena)"
                    sync.synced -> "Synced ✓"
                    else -> "Not synced: tap Sync and scan a synced player's marker"
                },
                color = if (sync.synced) Color(0xFF69F0AE) else Color(0xFFFFC107), fontSize = 13.sp,
            )
        }
        OutlinedButton(onClick = vm::openArenaSync, enabled = lobby.arena.epoch > 0) { Text(if (sync.synced) "Share sync" else "Sync") }
    }
    if (state.isHost) {
        // Picking a reference starts a fresh arena; everyone else re-syncs from it.
        ChoiceRow("Arena", lobby.players.map { it.id to it.name }, lobby.arena.referenceId) { if (it != lobby.arena.referenceId) vm.resetArena(it) }
    }
}

private fun GameMode.label() = if (this == GameMode.TEAMS) "Teams" else "Free for all"

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()), // up to 16 player names in the arena row
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, modifier = Modifier.width(56.dp), color = Color.Gray)
        options.forEach { (value, text) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

/**
 * "Random hunters": who shot the most bystanders this match. Only counted when the camera saw a
 * person in the crosshair and no player was anywhere near the line of fire.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.randomsLeaderboard(results: ServerMessage.MatchEnded, state: UiState) {
    val hunters = results.scores.filter { it.randoms > 0 }.sortedByDescending { it.randoms }
    item {
        Spacer(Modifier.height(16.dp))
        Text("Random hunters", style = MaterialTheme.typography.titleMedium, color = RandomOrange)
        Text("Shots that hit a bystander instead of a player", color = Color.Gray, fontSize = 13.sp)
    }
    if (hunters.isEmpty()) {
        item { Text("Nobody shot a random. Clean match!", color = Color.LightGray, modifier = Modifier.padding(vertical = 8.dp)) }
    }
    items(hunters.size) { i ->
        val line = hunters[i]
        Row(
            Modifier.fillMaxWidth()
                .background(if (i == 0) RandomOrange.copy(alpha = 0.25f) else Color(0x18FFFFFF), RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text(if (i == 0) "🙈" else "${i + 1}", Modifier.width(28.dp))
            Text(line.name + if (line.id == state.myId) " (you)" else "", Modifier.weight(1f))
            Text("${line.randoms} random${if (line.randoms == 1) "" else "s"}", fontWeight = FontWeight.Bold, color = RandomOrange)
        }
    }
}

@Composable
private fun ResultsScreen(vm: GameViewModel, state: UiState, results: ServerMessage.MatchEnded) {
    val headline = when {
        results.winnerTeam != null -> "${results.winnerTeam!!.name.lowercase().replaceFirstChar { it.uppercase() }} team wins!"
        results.winnerId == state.myId -> "You win!"
        results.winnerId != null -> "${state.nameOf(results.winnerId!!)} wins!"
        else -> "It's a draw"
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Match over", color = Color.Gray)
        Text(headline, fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.padding(horizontal = 12.dp)) {
            Text("#", Modifier.width(28.dp), color = Color.Gray)
            Text("Player", Modifier.weight(1f), color = Color.Gray)
            Text("Tags", Modifier.width(56.dp), color = Color.Gray, textAlign = TextAlign.End)
            Text("Outs", Modifier.width(56.dp), color = Color.Gray, textAlign = TextAlign.End)
            Text("Rnd", Modifier.width(48.dp), color = Color.Gray, textAlign = TextAlign.End)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results.scores.size) { i ->
                val line = results.scores[i]
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (line.id == state.myId) Color(0x33FF3D3D) else Color(0x18FFFFFF), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text("${i + 1}", Modifier.width(28.dp))
                    Text(line.name, Modifier.weight(1f), color = if (line.team != Team.NONE) line.team.color() else Color.Unspecified)
                    Text("${line.score}", Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                    Text("${line.deaths}", Modifier.width(56.dp), textAlign = TextAlign.End)
                    Text("${line.randoms}", Modifier.width(48.dp), textAlign = TextAlign.End, color = if (line.randoms > 0) RandomOrange else Color.Unspecified)
                }
            }
            randomsLeaderboard(results, state)
        }
        if (state.isHost) {
            Button(onClick = vm::backToLobby, modifier = Modifier.fillMaxWidth()) { Text("Back to lobby") }
        } else {
            Hint("Waiting for the host…")
        }
        OutlinedButton(onClick = vm::leave, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
    }
}
