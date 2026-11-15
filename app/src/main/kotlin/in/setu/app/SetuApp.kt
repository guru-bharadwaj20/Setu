package `in`.setu.app

import android.app.Application

/**
 * Application entry point. Holds the single [Graph] of long-lived objects so
 * the service, the receivers and the UI share one store and one identity.
 */
class SetuApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    companion object {
        fun graph(app: Application): Graph = (app as SetuApp).graph
    }
}
