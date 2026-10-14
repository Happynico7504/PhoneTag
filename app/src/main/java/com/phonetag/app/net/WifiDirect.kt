package com.phonetag.app.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.phonetag.core.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A device found nearby. [lobbyName] is set when it advertises a PhoneTag game over DNS-SD. */
data class NearbyGame(val deviceAddress: String, val deviceName: String, val lobbyName: String?)

/** Credentials of the host's Wi-Fi Direct group (others can also join it like a normal hotspot). */
data class GroupInfo(val ssid: String, val passphrase: String)

/**
 * Wraps Android's Wi-Fi Direct (Wi-Fi P2P) APIs for PhoneTag.
 *
 * Host: creates an autonomous group (becomes group owner, reachable at 192.168.49.1) and
 * advertises a `_phonetag._tcp` DNS-SD service so joiners can see the game by name.
 * Joiner: discovers services/peers, connects with groupOwnerIntent 0, and reports the group
 * owner's IP so the app can open the TCP game connection to it.
 *
 * Callers must hold NEARBY_WIFI_DEVICES (Android 13+) or ACCESS_FINE_LOCATION (older).
 */
@SuppressLint("MissingPermission")
class WifiDirect(private val context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)
    private val main = Handler(Looper.getMainLooper())

    private val _nearby = MutableStateFlow<List<NearbyGame>>(emptyList())
    val nearby: StateFlow<List<NearbyGame>> = _nearby.asStateFlow()

    private val _groupInfo = MutableStateFlow<GroupInfo?>(null)
    val groupInfo: StateFlow<GroupInfo?> = _groupInfo.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isSupported get() = manager != null && channel != null

    private var onGroupOwnerAddress: ((String) -> Unit)? = null
    private var receiverRegistered = false
    private var hosting = false
    private val advertisedNames = mutableMapOf<String, String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _enabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
            }
        }
    }

    private val keepDiscoverable = object : Runnable {
        override fun run() {
            // Many devices only answer service discovery while they are discovering themselves.
            manager?.discoverPeers(channel, NoopListener("discoverPeers(host)"))
            main.postDelayed(this, 25_000)
        }
    }

    fun register() {
        if (receiverRegistered || !isSupported) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    // ------------------------------------------------------------------ host

    /** Creates a Wi-Fi Direct group with this phone as owner and advertises the game. */
    fun host(lobbyName: String, onReady: () -> Unit, onError: (String) -> Unit) {
        val m = manager ?: return onError("Wi-Fi Direct is not supported on this device")
        register()
        hosting = true
        // Leave any stale group first; ignore the result.
        m.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create()
            override fun onFailure(reason: Int) = create()
            fun create() {
                m.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        advertise(lobbyName)
                        onReady()
                    }
                    override fun onFailure(reason: Int) = onError("Could not create Wi-Fi Direct group (${reasonText(reason)})")
                })
            }
        })
    }

    private fun advertise(lobbyName: String) {
        val record = mapOf("name" to lobbyName.take(40), "port" to Protocol.DEFAULT_PORT.toString(), "v" to Protocol.VERSION.toString())
        val info = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, record)
        manager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = add()
            override fun onFailure(reason: Int) = add()
            fun add() = manager.addLocalService(channel, info, NoopListener("addLocalService"))
        })
        main.removeCallbacks(keepDiscoverable)
        main.post(keepDiscoverable)
    }

    // ------------------------------------------------------------------ join

    /** Starts looking for nearby PhoneTag hosts (DNS-SD) and plain Wi-Fi Direct peers. */
    fun startDiscovery() {
        val m = manager ?: return
        register()
        _nearby.value = emptyList()
        advertisedNames.clear()
        m.setDnsSdResponseListeners(
            channel,
            { instanceName, registrationType, device ->
                if (instanceName == SERVICE_INSTANCE && registrationType.startsWith(SERVICE_TYPE)) {
                    upsert(device, advertisedNames[device.deviceAddress])
                }
            },
            { fullDomain, record, device ->
                if (fullDomain.startsWith(SERVICE_INSTANCE, ignoreCase = true)) {
                    val name = record["name"] ?: device.deviceName
                    advertisedNames[device.deviceAddress] = name
                    upsert(device, name)
                }
            },
        )
        m.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = request()
            override fun onFailure(reason: Int) = request()
            fun request() {
                m.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), NoopListener("addServiceRequest"))
                m.discoverServices(channel, NoopListener("discoverServices"))
                m.discoverPeers(channel, NoopListener("discoverPeers"))
            }
        })
    }

    fun stopDiscovery() {
        manager?.stopPeerDiscovery(channel, NoopListener("stopPeerDiscovery"))
        manager?.clearServiceRequests(channel, NoopListener("clearServiceRequests"))
    }

    /** Connects to [game]'s group. [onConnected] receives the group owner's IP once the group forms. */
    fun join(game: NearbyGame, onConnected: (String) -> Unit, onError: (String) -> Unit) {
        val m = manager ?: return onError("Wi-Fi Direct is not supported on this device")
        register()
        hosting = false
        onGroupOwnerAddress = onConnected
        val config = WifiP2pConfig().apply {
            deviceAddress = game.deviceAddress
            groupOwnerIntent = 0 // we want the host to stay group owner
            wps.setup = WpsInfo.PBC
        }
        m.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit // wait for WIFI_P2P_CONNECTION_CHANGED_ACTION
            override fun onFailure(reason: Int) {
                onGroupOwnerAddress = null
                onError("Wi-Fi Direct connect failed (${reasonText(reason)})")
            }
        })
        // If we are already in that group, the broadcast won't fire again.
        onConnectionChanged()
    }

    // ------------------------------------------------------------------ teardown

    fun leave() {
        main.removeCallbacks(keepDiscoverable)
        onGroupOwnerAddress = null
        _groupInfo.value = null
        val m = manager ?: return
        if (hosting) m.clearLocalServices(channel, NoopListener("clearLocalServices"))
        hosting = false
        m.cancelConnect(channel, NoopListener("cancelConnect"))
        m.removeGroup(channel, NoopListener("removeGroup"))
    }

    fun close() {
        leave()
        stopDiscovery()
        unregister()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) channel?.close()
    }

    // ------------------------------------------------------------------ internals

    private fun requestPeers() {
        manager?.requestPeers(channel) { list ->
            list.deviceList.forEach { upsert(it, advertisedNames[it.deviceAddress]) }
            // Drop peers that disappeared, unless they advertised a game.
            val present = list.deviceList.map { it.deviceAddress }.toSet()
            _nearby.update { games -> games.filter { it.deviceAddress in present || it.lobbyName != null } }
        }
    }

    private fun upsert(device: WifiP2pDevice, lobbyName: String?) {
        _nearby.update { games ->
            val existing = games.firstOrNull { it.deviceAddress == device.deviceAddress }
            val merged = NearbyGame(
                deviceAddress = device.deviceAddress,
                deviceName = device.deviceName.ifBlank { device.deviceAddress },
                lobbyName = lobbyName ?: existing?.lobbyName,
            )
            (games.filter { it.deviceAddress != device.deviceAddress } + merged)
                .sortedWith(compareBy<NearbyGame> { it.lobbyName == null }.thenBy { it.deviceName })
        }
    }

    private fun onConnectionChanged() {
        val m = manager ?: return
        m.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            if (info.isGroupOwner) {
                m.requestGroupInfo(channel) { group ->
                    if (group != null) _groupInfo.value = GroupInfo(group.networkName, group.passphrase ?: "")
                }
            } else {
                val address = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                onGroupOwnerAddress?.let {
                    onGroupOwnerAddress = null
                    it(address)
                }
            }
        }
    }

    private class NoopListener(private val what: String) : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) {
            Log.d(TAG, "$what failed: ${reasonText(reason)}")
        }
    }

    companion object {
        private const val TAG = "WifiDirect"
        const val SERVICE_INSTANCE = "PhoneTag"
        const val SERVICE_TYPE = "_phonetag._tcp"

        fun reasonText(reason: Int) = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.BUSY -> "busy, try again"
            WifiP2pManager.ERROR -> "internal error"
            else -> "code $reason"
        }
    }
}
