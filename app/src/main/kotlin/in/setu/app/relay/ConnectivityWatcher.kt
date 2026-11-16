package `in`.setu.app.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * True only when the default network has validated internet access, not merely
 * a Wi-Fi association. A captive portal or a dead tower does not count.
 */
class ConnectivityWatcher(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.isValidated()
        }

        override fun onLost(network: Network) {
            _online.value = false
        }
    }

    fun start() {
        if (registered) return
        registered = true
        _online.value = current()
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun current(): Boolean = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.isValidated() ?: false

    private fun NetworkCapabilities.isValidated() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
