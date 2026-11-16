package `in`.setu.app

import android.content.Context
import `in`.setu.app.data.RoomPacketStorage
import `in`.setu.app.data.SetuDatabase
import `in`.setu.app.identity.IdentityStore
import `in`.setu.app.location.LocationProvider
import `in`.setu.app.mesh.BatteryMonitor
import `in`.setu.app.mesh.MeshStats
import `in`.setu.app.relay.ConnectivityWatcher
import `in`.setu.app.relay.RelayUploader
import `in`.setu.app.settings.Settings
import `in`.setu.app.sos.SosComposer
import `in`.setu.app.sos.SosController
import `in`.setu.routing.PacketStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-wired dependency graph. Small enough that a DI framework would cost
 * more than it saves. Everything here lives as long as the process.
 */
class Graph(val context: Context) {
    val clock: () -> Long = { System.currentTimeMillis() / 1000 }

    /** Background work that outlives any one activity or service instance. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db by lazy { SetuDatabase.open(context) }
    val packetDao get() = db.packets()

    /** Loads the whole table on first access, so only touch it off the main thread. */
    val store by lazy { PacketStore(RoomPacketStorage(packetDao), clock = clock) }

    val identityStore by lazy { IdentityStore(context) }
    val settings = Settings(context)
    val location = LocationProvider(context)
    val battery = BatteryMonitor(context)
    val stats = MeshStats()
    val connectivity = ConnectivityWatcher(context)

    val composer by lazy { SosComposer(identityStore.identity, clock) }
    val sos by lazy { SosController(this) }
    val uploader by lazy { RelayUploader(this) }
}
