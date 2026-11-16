package `in`.setu.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.setu.app.Graph
import `in`.setu.app.SetuApp
import `in`.setu.app.mesh.MeshService
import `in`.setu.app.mesh.MeshState
import `in`.setu.app.sos.SosInput
import `in`.setu.protocol.PacketType
import `in`.setu.protocol.ShortCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Builds view models from the app [Graph] without a DI framework. */
class SetuViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val g = SetuApp.graph(app)
        return when {
            modelClass.isAssignableFrom(SosViewModel::class.java) -> SosViewModel(g)
            modelClass.isAssignableFrom(CourierViewModel::class.java) -> CourierViewModel(g)
            modelClass.isAssignableFrom(StatusViewModel::class.java) -> StatusViewModel(g)
            else -> throw IllegalArgumentException("unknown view model $modelClass")
        } as T
    }
}

data class SosUiState(
    val active: Boolean = false,
    val since: Long = 0,
    val acked: Boolean = false,
    val sending: Boolean = false,
    val shortCode: String = "",
    val error: String? = null,
)

class SosViewModel(private val g: Graph) : ViewModel() {
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val shortCode = MutableStateFlow("")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            shortCode.value = ShortCode.display(ShortCode.of(g.identityStore.identity.publicKey))
        }
    }

    val state: StateFlow<SosUiState> = combine<Any?, SosUiState>(
        g.settings.activeSosIdHex, g.settings.activeSosSince, g.settings.sosAckedAt, sending, error, shortCode,
    ) { v ->
        SosUiState(
            active = v[0] != null,
            since = v[1] as Long,
            acked = (v[2] as Long) > 0,
            sending = v[3] as Boolean,
            shortCode = v[5] as String,
            error = v[4] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SosUiState())

    fun send(headcount: Int, needs: Int, note: String) {
        if (sending.value) return
        sending.value = true
        error.value = null
        viewModelScope.launch {
            try {
                g.sos.send(SosInput(headcount, needs, note))
                MeshService.start(g.context)
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                sending.value = false
            }
        }
    }

    fun markSafe() {
        viewModelScope.launch {
            try {
                g.sos.markSafe()
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }
}

class CourierViewModel(private val g: Graph) : ViewModel() {
    val enabled: StateFlow<Boolean> = g.settings.courierEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val mesh: StateFlow<MeshState> = g.stats.state

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            g.settings.setCourierEnabled(on)
            if (on) MeshService.start(g.context) else if (!g.settings.snapshot().sosActive) MeshService.stop(g.context)
        }
    }
}

data class StatusUiState(
    val mesh: MeshState = MeshState(),
    val stored: Int = 0,
    val sosCarried: Int = 0,
    val lastUploadAt: Long = 0,
    val relayUrl: String = "",
    val shortCode: String = "",
)

class StatusViewModel(private val g: Graph) : ViewModel() {
    private val shortCode = MutableStateFlow("")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            shortCode.value = ShortCode.display(ShortCode.of(g.identityStore.identity.publicKey))
        }
    }

    val state: StateFlow<StatusUiState> = combine<Any?, StatusUiState>(
        g.stats.state,
        g.packetDao.count(),
        g.packetDao.countByType(PacketType.SOS.code),
        g.settings.lastUploadAt,
        g.settings.relayUrl,
        shortCode,
    ) { v ->
        StatusUiState(
            mesh = v[0] as MeshState,
            stored = v[1] as Int,
            sosCarried = v[2] as Int,
            lastUploadAt = v[3] as Long,
            relayUrl = v[4] as String,
            shortCode = v[5] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    fun setRelayUrl(url: String) {
        viewModelScope.launch { g.settings.setRelayUrl(url) }
    }

    fun uploadNow() {
        viewModelScope.launch { withContext(Dispatchers.IO) { g.uploader.uploadAll() } }
    }
}
