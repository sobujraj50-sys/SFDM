package com.sfdm.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class Transport { WIFI, CELLULAR, OTHER }

/**
 * A "lane" is one network path a download can use.
 * [network] == null means "the phone's current default network" (Auto mode).
 */
class Lane(val network: Network?, val fixedTransport: Transport?, val client: OkHttpClient)

/**
 * Knows which networks are up, can hold Wi-Fi or mobile data open on request,
 * and builds OkHttp clients bound to a specific network.
 */
class NetworkProvider(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    private val boundClients = ConcurrentHashMap<Network, OkHttpClient>()

    /** Emits whenever any network becomes available or is lost. */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = _changes

    // Held network requests (ref-counted) so mobile data stays up while Wi-Fi is connected.
    private class Held(val callback: ConnectivityManager.NetworkCallback) {
        @Volatile var network: Network? = null
        var refs = 0
    }
    private val held = HashMap<Transport, Held>()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _changes.tryEmit(Unit) }
            override fun onLost(network: Network) { _changes.tryEmit(Unit) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _changes.tryEmit(Unit) }
        })
    }

    fun hasInternet(): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun defaultTransport(): Transport? {
        val n = cm.activeNetwork ?: return null
        return transportOf(n)
    }

    fun transportOf(n: Network): Transport? {
        val caps = cm.getNetworkCapabilities(n) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }

    fun isAlive(lane: Lane): Boolean {
        val n = lane.network ?: return hasInternet()
        return cm.getNetworkCapabilities(n) != null
    }

    fun autoLane() = Lane(null, null, baseClient)

    fun laneFor(network: Network, transport: Transport) = Lane(network, transport, clientFor(network))

    private fun clientFor(network: Network): OkHttpClient = boundClients.getOrPut(network) {
        baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * Asks Android to bring up (or keep up) a Wi-Fi or cellular network and returns it.
     * Returns null if it isn't available within [timeoutMs]. Call [release] when done.
     */
    suspend fun acquire(transport: Transport, timeoutMs: Long): Network? {
        val h = synchronized(held) {
            held.getOrPut(transport) {
                val holder = arrayOfNulls<Held>(1)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        holder[0]?.network = network
                        _changes.tryEmit(Unit)
                    }
                    override fun onLost(network: Network) {
                        if (holder[0]?.network == network) holder[0]?.network = null
                        boundClients.remove(network)
                        _changes.tryEmit(Unit)
                    }
                }
                val held1 = Held(cb)
                holder[0] = held1
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(
                        if (transport == Transport.WIFI) NetworkCapabilities.TRANSPORT_WIFI
                        else NetworkCapabilities.TRANSPORT_CELLULAR,
                    )
                    .build()
                try {
                    cm.requestNetwork(req, cb)
                } catch (e: Exception) {
                    // Some devices refuse; we simply report the network as unavailable.
                }
                held1
            }.also { it.refs++ }
        }
        val net = withTimeoutOrNull(timeoutMs) {
            while (h.network == null) delay(200)
            h.network
        }
        if (net == null) release(transport)
        return net
    }

    fun release(transport: Transport) {
        synchronized(held) {
            val h = held[transport] ?: return
            h.refs--
            if (h.refs <= 0) {
                runCatching { cm.unregisterNetworkCallback(h.callback) }
                held.remove(transport)
            }
        }
    }
}
