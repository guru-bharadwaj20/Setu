package `in`.setu.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.setu.app.SetuApp
import kotlinx.coroutines.launch

/**
 * Brings the mesh back after a reboot or an app update, but only if the user
 * had courier mode on or an SOS in flight. A fresh install does nothing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val g = SetuApp.graph(context.applicationContext as android.app.Application)
        val pending = goAsync()
        g.scope.launch {
            try {
                val s = g.settings.snapshot()
                if (s.courierEnabled || s.sosActive) MeshService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
