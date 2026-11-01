package app.omnisonic.playback.cache

import androidx.media3.datasource.DataSource

class DataSourceRegistry {
    private val factories = mutableMapOf<String, DataSource.Factory>()

    @Synchronized
    fun register(id: String, factory: DataSource.Factory) {
        factories[id] = factory
    }

    @Synchronized
    fun get(id: String): DataSource.Factory? = factories[id]

    @Synchronized
    fun unregister(id: String) {
        factories.remove(id)
    }

    @Synchronized
    fun clear() {
        factories.clear()
    }
}
