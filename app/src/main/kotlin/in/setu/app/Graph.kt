package `in`.setu.app

import android.content.Context

/** Hand-wired dependency graph. Small enough that a DI framework would cost more than it saves. */
class Graph(val context: Context) {
    val clock: () -> Long = { System.currentTimeMillis() / 1000 }
}
