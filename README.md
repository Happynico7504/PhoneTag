# PhoneTag

Laser tag for Android phones. Every phone tracks itself in 3D with **ARCore camera tracking**,
backed up by GPS and compass, and all phones share one coordinate frame. You aim by pointing
your phone's camera at another player and hitting **FIRE**. The authoritative game server checks
whether the other player was in your line of fire and whether a wall was in the way.

**AR tracking is mandatory, so the game is fair:** everyone's hits are judged the same precise way
(see [Fair play](#fair-play-ar-is-required)).

- **Wi-Fi Direct first.** One phone hosts and the others join it directly. No router or internet needed.
- **Dedicated server fallback.** If players are too far apart for Wi-Fi Direct, run
  `phonetag-server` somewhere reachable and play over mobile data or any Wi-Fi.
- **Lobbies are capped at 16 players.** The cap is enforced on the server and can't be raised by a host.

## How it works

```
 ┌──────────── Wi-Fi Direct group ────────────┐        ┌──────── Online ────────┐
 │  Host phone (group owner, 192.168.49.1)    │        │  phonetag-server       │
 │   ├─ GameServer (single lobby, port 47474) │        │   ├─ many lobbies      │
 │   └─ GameClient → 127.0.0.1                │        │   │  (5-char codes,    │
 │  Joiner phones ── GameClient → host:47474  │        │   │   16 players each) │
 └────────────────────────────────────────────┘        │  Phones ── TCP 47474   │
                                                        └────────────────────────┘
```

Both modes use exactly the same code (`core/`): the same `GameServer`, `Lobby` engine and
newline-delimited JSON protocol over TCP. The host phone just runs the server in-process.

### Tracking & hit detection
- Each phone sends its pose (lat/lon, GPS accuracy, compass azimuth corrected to true north)
  5× per second. The server broadcasts everyone's positions, health and scores 5× per second.
  That data drives the in-game **radar**: heading-up, with the outer ring at max weapon range.
- A shot is a ray from the shooter along the compass heading. A target counts as hit if the
  bearing to it is inside a cone sized for a body radius plus part of both players' GPS
  error. The cone gets narrower with distance and is clamped between 2° and 30°. The closest,
  most centred target takes the hit, so the player in front blocks the one behind.
  See `core/.../HitResolver.kt`.
- The client runs the same check locally to turn the crosshair red when someone is in your sights.
- Rules: 100 HP, 25 damage per hit (4 hits = tagged out), 8 s respawn, 3 s spawn protection,
  350 ms fire cooldown, 60 m range by default. The server enforces all of them. Free-for-all
  or Red vs Blue teams (no friendly fire). Matches last 5, 10 or 15 min.

### Camera tracking & shared AR arena
On phones with ARCore, the camera also tracks the phone in 3D, accurate to a few centimetres.
Phones then **sync a shared coordinate frame (the "arena") with each other, offline**, with no
cloud anchors and no internet:

1. The lobby names a **reference phone**: the first AR-capable phone by default, and the host
   can pick another. Its ARCore world at the moment it starts tracking defines the arena.
2. A synced player opens **AR sync → Show my marker**. Their screen shows a generated image
   target at an exact physical size (5 cm) and keeps broadcasting the screen's pose in arena
   coordinates through the game server.
3. Another player points their camera at that screen from 20–40 cm away. ARCore measures the
   marker. Since `marker_in_my_world = arena_to_my_world × screen_in_arena`, the phone solves
   for `arena_to_my_world`. It averages 15 consistent samples, keeps only yaw and translation
   (both worlds are gravity-aligned), and stores the result as an ARCore anchor that keeps
   refining. Any synced phone can sync the next one.

How hits are decided (`core/.../HitResolver.kt`):

| Shooter / target | Method |
|---|---|
| Both synced to the same arena epoch | **3D ray cast** from the shooter's camera against a body-sized capsule around the target's phone. Tolerance is 0.5 m plus 2 cm per metre of distance. |
| Otherwise | **GPS + compass cone**, as before |
| ...plus on-device **person detection** (ML Kit pose detection, offline) | A person under the crosshair at a plausible distance **widens** the GPS cone. A clearly empty crosshair within 20 m **narrows** it, which also makes shooting through walls harder. |

AR markers in the view use the true 3D positions of synced players ("AR" in their label).
The detected person gets corner brackets. Between matches the host can restart the arena,
which starts a new epoch and everyone re-syncs.

### Fair play: AR is required
- **Install:** the manifest declares ARCore and `camera.ar` as required, so Google Play only
  offers PhoneTag to ARCore phones and installs Google Play Services for AR with it. A sideloaded
  copy on an unsupported phone stops at an "AR tracking required" screen.
- **Join:** every `Hello` says whether the phone runs AR tracking, and the server (host phone or
  dedicated) turns away phones that don't (`AR_REQUIRED`).
- **Start:** the host can only start once **every** player is synced and tracking in the
  arena. The lobby shows "not synced" next to anyone who isn't, and keeps a small live AR
  camera running so players stay tracked until the match starts. The arena can't be reset
  during a match.
- **Shoot:** a shot only counts if the shooter's phone is tracking in the arena at that
  moment. Otherwise it's rejected with "NOT TRACKING" and the fire button is disabled until
  tracking returns.
- **Get hit:** a target whose tracking drops stays hittable through their GPS position, so
  losing tracking, or covering the camera, can never make you harder to hit.
- **Re-sync mid-match:** synced players can tap **Share sync** in the HUD to show their marker,
  and an unsynced player re-syncs by scanning it.
- **Radar:** uses exact arena positions between synced players.

### Obstacles: no shooting through walls
Every shot that would hit is also checked for line of sight (`core/.../Occlusion.kt`, `HitResolver`):

- **Depth at the crosshair.** AR phones run the ARCore Depth API (depth from motion, or a ToF
  sensor if the phone has one) and measure the depth straight down the crosshair about 10 times a
  second. The value is sent with each shot. If something solid is clearly closer than the target,
  and within about 8 m where depth is reliable, the shot is **blocked**. With precise arena
  distances that means more than 0.75 m in front of the target. With GPS distances the margin
  grows with GPS error, and the check is skipped if the camera sees a person in the crosshair,
  because the near surface is then most likely that person.
- **Shared wall map.** Synced AR phones detect vertical surfaces (ARCore plane finding) and
  report the largest ones as rectangles in arena coordinates every 2 s. The server merges them
  into one map for the arena epoch and keeps a wall even after the phone that found it leaves.
  It ray-casts every shot from a synced shooter against that map. Walls near the *target* count
  too, even when they're too far away for the shooter's depth sensor.
- **Feedback.** The crosshair turns grey with a slash when an enemy is lined up behind cover, a
  blocked shot plays a dull "thunk" with a debris puff and "BLOCKED", and known walls show as
  translucent panels in the AR view.


### Automatic flashlight
Every phone runs its own flashlight controller (`app/.../light/TorchController.kt`). Nothing
is synced, so each device reacts to its own surroundings. It turns the torch on when:
- the front **ambient light sensor** reads below 8 lux for 1 s, or
- ARCore reports tracking is failing for **insufficient light**, or
- the phone has no light sensor and the **camera image** is dark.

It turns the torch off after the light sensor reads above 35 lux for 3 s, with tracking stable
and at least 6 s after switching on. The gap between the two thresholds stops flickering. The
torch brightens the camera image itself, so phones without a light sensor briefly switch it off
every 20 s to measure the real ambient light. The HUD and the AR sync screen have a per-phone
**Light: Auto / On / Off** switch.

### Proximity voice radio
Players can talk over the radio **only when they're at least 10 m apart**. Up close you can
hear each other anyway, and keeping the radio muted there prevents feedback, where one phone's
speaker loops into a nearby phone's mic.

- **Routing** (`core/.../VoiceRouter.kt`): the server works out every pair's distance 5 times
  a second, using exact arena positions when both players are synced and GPS otherwise. A link
  opens after a pair has stayed beyond 10.5 m for 1 s, so GPS jitter doesn't make it flap. It
  closes instantly within 9.5 m. Unknown or stale positions count as "no link". In team mode only
  teammates are linked. Voice frames are forwarded over open links only, and never back to the
  sender.
- **Audio** (`app/.../voice/`): 16 kHz mono from the phone's voice-communication mic, with the
  platform echo canceller and noise suppressor on. Voice activity detection with an adaptive
  noise floor means only speech is sent, and only while someone is in radio range. 40 ms
  IMA-ADPCM frames (4:1 compression, about 64 kbit/s while talking) travel over the existing
  game connection, so voice works the same over Wi-Fi Direct and the dedicated server, with no
  extra ports or native libraries. Each frame decodes on its own, so a lost frame costs only
  40 ms. Voice is sent as droppable data and discarded rather than queued when a link is busy,
  so it can never get a player disconnected.
- **Playback:** small per-talker jitter buffers (80 ms) are mixed into one stream, played on a
  connected headset if there is one, otherwise the loudspeaker.
- **UI:** a mic switch and a "Radio: …" line showing who you're connected to, with 🔊 on
  whoever is talking, both on the AR markers and in the HUD. The radar shows a dashed 10 m
  ring. The microphone permission is optional: without it you can still listen.

### Random shots ("randoms")
On-device person detection also catches players shooting **bystanders**. A missed shot counts
as a random when all of these hold (`HitResolver.isBystanderShot`):
- the shooter's camera saw a person under the crosshair (ML Kit pose detection);
- the shot hit no player, and wasn't stopped by an obstacle;
- **no** player of any team, alive or not, is anywhere near the line of fire. "Near" is generous:
  at least 15°, or double the normal hit tolerance if that's wider. A narrowly missed player
  therefore never counts as a random;
- the person isn't clearly out of range by their estimated distance.

The server counts randoms per player for each match and announces each one to everyone. It
appears as an orange line in the kill feed, "Alex shot a random! (3)". The shooter gets a comic
slide-whistle sound, a "RANDOM! ×3" banner and an orange "NOT A PLAYER" box around the person
they hit. The HUD shows your running count. The end-of-match screen adds a **Rnd** column and a
**Random hunters** leaderboard. No camera images ever leave the phone, only the yes/no "person
in crosshair" flag already sent with every shot.

### AR view & sound
- **Player markers in the camera image.** Each player's GPS bearing is projected into the viewfinder
  using the real camera's field of view (read from the lens focal length and sensor size).
  Each marker shows a name, distance and health bar. Enemies are diamonds, teammates are
  chevrons, and tagged-out players are grey X's. Players outside the view appear as stacked
  arrows on the screen edge. Markers are smoothed so GPS jitter doesn't make them jump.
- **Effects:** laser beam and muzzle flash on fire, spinning lock-on brackets, a hit marker
  with sparks, a shockwave and a "+1 TAG" banner on tag-outs, a red damage vignette with
  screen shake and an arrow pointing at whoever shot you, a heartbeat vignette at low health,
  a blue spawn-shield glow, a "signal lost" glitch while tagged out, and a radar sweep.
  See `app/.../ui/ArOverlay.kt`.
- **Sound effects** are synthesized in code (`app/.../sfx/Synth.kt`), so the app ships no audio
  files. They're rendered to WAV once and played through `SoundPool`: laser, hit confirm,
  tag, damage, tagged out, respawn, lock-on, match start/end and a low-health heartbeat.
  You can mute them from the in-game HUD.

### Limitations to know about
- **Without AR sync, GPS is the precision limit.** Phone GPS is typically good to ±3–10 m outdoors and much
  worse indoors. Play outside with open sky, and spread out more than a few metres. The app
  shows your current GPS accuracy, and the hit cone widens to compensate.
- **AR sync accuracy** depends mostly on how still both phones are while scanning. A 1° heading
  error moves a target 50 cm at 30 m. If the arena drifts after a long match, the host can
  restart it. Pausing the camera, for example by leaving the app, can make ARCore lose its map;
  re-sync if the lobby stops showing you as "AR synced".
- **Compass calibration matters.** If the app says the compass is unreliable, wave the phone in a figure 8.
- **Wi-Fi Direct client limits vary by phone.** The protocol allows 16 players, but some
  phones only accept about 8 clients in a Wi-Fi Direct group. If people can't join, use the
  dedicated server. Wi-Fi Direct range is roughly 50–100 m outdoors.
- Depending on the phone, the host may see a system "Invitation to connect" prompt for each joiner.
- The host phone runs the server, so it must keep PhoneTag open. The app keeps the screen on.
  If the host leaves, the Wi-Fi Direct game ends. On the dedicated server, the host role passes
  to the next player.

## Project layout

| Module    | What                                                                                          |
|-----------|-----------------------------------------------------------------------------------------------|
| `core/`   | Pure Kotlin/JVM: protocol, `Lobby` game engine, `HitResolver`, `GameServer`, `GameClient`. Unit + socket tests. |
| `server/` | Dedicated server CLI (`phonetag-server`) + Dockerfile.                                        |
| `app/`    | Android app (Kotlin, Jetpack Compose, ARCore, ML Kit pose detection, fused location, rotation-vector compass, Wi-Fi P2P). |

## Building

Requires JDK 17+ and, for the app, the Android SDK (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew :core:test            # engine + networking tests (incl. 17th player rejected)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :server:installDist   # server/build/install/phonetag-server/bin/phonetag-server
```

The GitHub Actions workflow builds all three and uploads the debug APK as an artifact.

## Running the dedicated server

```bash
server/build/install/phonetag-server/bin/phonetag-server --port 47474
# or
docker build -f server/Dockerfile -t phonetag-server .
docker run -p 47474:47474 phonetag-server
```

### Updating a running server
`server/update.sh` pulls the latest code, builds the server and swaps it into a running instance
without recreating anything. Afterwards it checks that the server came back up, and if not it
automatically rolls back to the previous version.

```bash
server/update.sh --docker phonetag                    # Docker: update the container named "phonetag"
server/update.sh --docker phonetag --rebuild-image phonetag-server   # ...and keep the image in sync
sudo server/update.sh --native /opt/phonetag-server   # native install (+ restarts the systemd service)
server/update.sh                                      # auto-detect: container "phonetag", else /opt/phonetag-server
server/update.sh --rollback --docker phonetag         # go back to the version before the last update
```

- **Build:** uses your JDK if it's 17 or newer, otherwise builds inside a throwaway
  `gradle:8.14.3-jdk17` container, so the host needs neither Java nor the Android SDK. Under
  `sudo` it still builds as your own user.
- **Docker:** backs up the container's `/opt/phonetag-server`, kept in
  `~/.local/state/phonetag/` for `--rollback`, copies the new build in with `docker cp`,
  restarts the container and waits for the "listening" log line. The container keeps its
  name, ports and settings. Without `--rebuild-image`, recreating the container later from
  the old image brings the old version back.
- **Native:** stages the build next to the install, swaps the directories in one step, keeping
  the previous one as `DIR.old` for `--rollback`, and restarts the systemd service
  (`--service NAME`, default `phonetag-server`). It then waits until the port answers. Without
  a service it just installs and tells you to restart the process. `server/phonetag-server.service`
  is a ready-made, hardened systemd unit with install steps.
- **Restarting drops current lobbies**, because they only live in memory. The script prints the
  protocol version the new server speaks, so you know which app build players need.

Open TCP port 47474 in your firewall. In the app, go to **Play online**, choose **Custom server**
and enter `host:47474`. Then either create a lobby and share its 5-letter code, or join from the
open-lobby list.

The **Official server** choice, selected by default, connects to the public instance at
`phonetag.nicochristmann.net:47474` without typing anything. Your custom address is kept
separately, so you can switch back and forth.

**Favorites:** with a custom address entered, tap **☆ Save** to store it under a name. Saved
servers appear as a list in the online menu: tap one to switch to it and load its lobbies, or
tap ✕ to remove it. Favorites live on the phone, newest first, up to 20. The same server
written differently, like `Host` vs `host:47474`, is only saved once.

## Playing

1. Everyone installs the APK on an ARCore-supported phone and grants camera, location and nearby-devices permissions.
2. **Nearby:** one player taps **Host a game**. The others tap **Join a nearby game** and pick it.
   **Far apart:** use **Play online** with a dedicated server.
3. Everyone syncs AR: the first phone in the lobby defines the arena. Each other player taps
   **Sync** and scans the marker of anyone already synced, who taps **Share sync**, from 20–40 cm away.
4. The host picks the mode, time and range, then taps **Start match** once everyone shows "AR synced".
5. Hold the phone upright like a camera, find players on the radar, put them in the crosshair and **FIRE**.
