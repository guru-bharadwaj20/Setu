package `in`.setu.app.mesh

import `in`.setu.routing.DutyCycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live counters for the Status screen and the notification. Reset on process start. */
data class MeshState(
    val serviceRunning: Boolean = false,
    val radioOn: Boolean = false,
    val duty: DutyCycle = DutyCycle.HIGH,
    val peersMet: Int = 0,
    val exchanges: Int = 0,
    val packetsReceived: Int = 0,
    val packetsSent: Int = 0,
    val online: Boolean = false,
    val uploaded: Int = 0,
    val lastError: String? = null,
)

class MeshStats {
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state

    fun update(f: (MeshState) -> MeshState) = _state.update(f)

    fun peerMet() = update { it.copy(peersMet = it.peersMet + 1) }

    fun exchangeDone(sent: Int, received: Int) = update {
        it.copy(exchanges = it.exchanges + 1, packetsSent = it.packetsSent + sent, packetsReceived = it.packetsReceived + received)
    }

    fun uploaded(n: Int) = update { it.copy(uploaded = it.uploaded + n) }

    fun error(message: String?) = update { it.copy(lastError = message) }
}
