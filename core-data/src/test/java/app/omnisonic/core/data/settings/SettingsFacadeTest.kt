package app.omnisonic.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsFacadeTest {

    @TempDir
    lateinit var tempDir: File

    private var facadeScope: CoroutineScope? = null
    private var ioScope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        facadeScope?.cancel()
        ioScope?.cancel()
        facadeScope = null
        ioScope = null
    }

    private val pageSize = SettingsKey.int("search.page_size", 30)
    private val threshold = SettingsKey.int("history.play_threshold_percent", 95)
    private val workerCount = SettingsKey.int("downloads.worker_count", 4)
    private val wifiOnly = SettingsKey.boolean("downloads.wifi_only", false)
    private val navOverride = SettingsKey.stringOrNull("shell.nav_override_portrait")
    private val hiddenIds = SettingsKey.stringSet("providers.hidden_ids")

    private fun newFacade(): SettingsFacade {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        facadeScope = scope
        return SettingsFacade(scope)
    }

    @Test
    fun `absent keys emit their documented defaults`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()

        assertThat(withTimeout(2_000) { facade.flow(store, pageSize).first() }).isEqualTo(30)
        assertThat(withTimeout(2_000) { facade.flow(store, wifiOnly).first() }).isEqualTo(false)
        assertThat(withTimeout(2_000) { facade.flow(store, navOverride).first() }).isNull()
        assertThat(withTimeout(2_000) { facade.flow(store, hiddenIds).first() }).isEmpty()
    }

    @Test
    fun `a key emits the written value on its own flow`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.Default) { facade.flow(store, pageSize).collect { emissions += it } }

        withTimeout(2_000) { while (emissions.isEmpty()) delay(10) }
        store.edit { it[intPreferencesKey(pageSize.name)] = 42 }
        withTimeout(2_000) { while (emissions.size < 2) delay(10) }
        job.cancelAndJoin()

        assertThat(emissions).containsExactly(30, 42)
    }

    @Test
    fun `a write to one key emits nothing on another key's flow`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()
        val pageEmissions = mutableListOf<Int>()
        val wifiEmissions = mutableListOf<Boolean>()
        val pageJob = launch(Dispatchers.Default) { facade.flow(store, pageSize).collect { pageEmissions += it } }
        val wifiJob = launch(Dispatchers.Default) { facade.flow(store, wifiOnly).collect { wifiEmissions += it } }
        withTimeout(2_000) { while (pageEmissions.isEmpty() || wifiEmissions.isEmpty()) delay(10) }

        store.edit { it[booleanPreferencesKey(wifiOnly.name)] = true }

        withTimeout(2_000) { while (wifiEmissions.size < 2) delay(10) }
        val pageGrew = withTimeoutOrNull(500) { while (pageEmissions.size < 2) delay(10) }
        pageJob.cancelAndJoin()
        wifiJob.cancelAndJoin()

        assertThat(pageGrew).isNull()
        assertThat(pageEmissions).containsExactly(30)
        assertThat(wifiEmissions).containsExactly(false, true)
    }

    @Test
    fun `rewriting the same value does not re-emit`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.Default) { facade.flow(store, pageSize).collect { emissions += it } }
        withTimeout(2_000) { while (emissions.isEmpty()) delay(10) }

        store.edit { it[intPreferencesKey(pageSize.name)] = 42 }
        withTimeout(2_000) { while (emissions.size < 2) delay(10) }
        store.edit { it[intPreferencesKey(pageSize.name)] = 42 }
        val grew = withTimeoutOrNull(500) { while (emissions.size < 3) delay(10) }
        job.cancelAndJoin()

        assertThat(grew).isNull()
        assertThat(emissions).containsExactly(30, 42)
    }

    @Test
    fun `a write reaches only the flows of its own store`(): Unit = runBlocking {
        val facade = newFacade()
        val first = FakeDataStore()
        val second = FakeDataStore()
        val firstValues = mutableListOf<Int>()
        val secondValues = mutableListOf<Int>()
        val firstJob = launch(Dispatchers.Default) { facade.flow(first, pageSize).collect { firstValues += it } }
        val secondJob = launch(Dispatchers.Default) { facade.flow(second, pageSize).collect { secondValues += it } }
        withTimeout(2_000) { while (firstValues.isEmpty() || secondValues.isEmpty()) delay(10) }

        first.edit { it[intPreferencesKey(pageSize.name)] = 42 }

        withTimeout(2_000) { while (firstValues.size < 2) delay(10) }
        val grew = withTimeoutOrNull(500) { while (secondValues.size < 2) delay(10) }
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()

        assertThat(grew).isNull()
        assertThat(firstValues).containsExactly(30, 42)
        assertThat(secondValues).containsExactly(30)
    }

    @Test
    fun `a DataStore read error emits each key's default instead of failing`(): Unit = runBlocking {
        val facade = newFacade()
        val failing = FailingDataStore()
        val pageValues = mutableListOf<Int>()
        val wifiValues = mutableListOf<Boolean>()
        val pageJob = launch(Dispatchers.Default) { facade.flow(failing, pageSize).collect { pageValues += it } }
        val wifiJob = launch(Dispatchers.Default) { facade.flow(failing, wifiOnly).collect { wifiValues += it } }

        withTimeout(2_000) { while (pageValues.isEmpty() || wifiValues.isEmpty()) delay(10) }
        pageJob.cancelAndJoin()
        wifiJob.cancelAndJoin()

        assertThat(pageValues).containsExactly(30)
        assertThat(wifiValues).containsExactly(false)
    }

    @Test
    fun `all key flows over one store share a single upstream collection`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()
        val pageValues = mutableListOf<Int>()
        val thresholdValues = mutableListOf<Int>()
        val workerValues = mutableListOf<Int>()
        val jobs = listOf(
            launch(Dispatchers.Default) { facade.flow(store, pageSize).collect { pageValues += it } },
            launch(Dispatchers.Default) { facade.flow(store, threshold).collect { thresholdValues += it } },
            launch(Dispatchers.Default) { facade.flow(store, workerCount).collect { workerValues += it } },
        )

        withTimeout(2_000) {
            while (pageValues.isEmpty() || thresholdValues.isEmpty() || workerValues.isEmpty()) delay(10)
        }
        jobs.forEach { it.cancelAndJoin() }

        assertThat(store.upstreamSubscriptions).isEqualTo(1)
        assertThat(pageValues).containsExactly(30)
        assertThat(thresholdValues).containsExactly(95)
        assertThat(workerValues).containsExactly(4)
    }

    @Test
    fun `each store gets its own shared upstream`(): Unit = runBlocking {
        val facade = newFacade()
        val first = FakeDataStore()
        val second = FakeDataStore()
        val jobs = listOf(
            launch(Dispatchers.Default) { facade.flow(first, pageSize).collect {} },
            launch(Dispatchers.Default) { facade.flow(second, pageSize).collect {} },
        )

        withTimeout(2_000) {
            while (first.upstreamSubscriptions == 0 || second.upstreamSubscriptions == 0) delay(10)
        }
        jobs.forEach { it.cancelAndJoin() }

        assertThat(first.upstreamSubscriptions).isEqualTo(1)
        assertThat(second.upstreamSubscriptions).isEqualTo(1)
    }

    @Test
    fun `one flow per key for a store and key pair`() {
        val facade = newFacade()
        val store = FakeDataStore()

        assertThat(facade.flow(store, pageSize)).isSameInstanceAs(facade.flow(store, pageSize))
        assertThat(facade.flow(store, wifiOnly)).isNotSameInstanceAs(facade.flow(store, pageSize))
        assertThat(facade.flow(FakeDataStore(), pageSize)).isNotSameInstanceAs(facade.flow(store, pageSize))
    }

    @Test
    fun `current returns the persisted value or the documented default`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()

        assertThat(withTimeout(2_000) { facade.current(store, pageSize) }).isEqualTo(30)

        store.edit { it[intPreferencesKey(pageSize.name)] = 42 }

        assertThat(withTimeout(2_000) { facade.current(store, pageSize) }).isEqualTo(42)
    }

    @Test
    fun `current returns the persisted value while the shared flow is paused`(): Unit = runBlocking {
        val facade = newFacade()
        val store = PausingDataStore()
        val job = launch(Dispatchers.Default) { facade.flow(store, pageSize).collect {} }
        withTimeout(2_000) { store.started.await() }

        store.edit { it[intPreferencesKey(pageSize.name)] = 42 }

        assertThat(withTimeout(2_000) { facade.current(store, pageSize) }).isEqualTo(42)
        store.release()
        job.cancelAndJoin()
    }

    @Test
    fun `concurrent first access starts one upstream per store`(): Unit = runBlocking {
        val facade = newFacade()
        val store = FakeDataStore()
        val barrier = CyclicBarrier(8)

        val values = (1..8).map { index ->
            async(Dispatchers.IO) {
                barrier.await()
                facade.flow(store, if (index % 2 == 0) pageSize else threshold).first()
            }
        }.awaitAll()

        assertThat(values).hasSize(8)
        assertThat(store.upstreamSubscriptions).isEqualTo(1)
    }

    @Test
    fun `a real DataStore write re-emits the key flow`(): Unit = runBlocking {
        val file = File(tempDir, "settings-${System.nanoTime()}.preferences_pb")
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope = storeScope
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
        val facade = newFacade()
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.Default) { facade.flow(dataStore, pageSize).collect { emissions += it } }

        withTimeout(2_000) { while (emissions.isEmpty()) delay(10) }
        dataStore.edit { it[intPreferencesKey(pageSize.name)] = 50 }
        withTimeout(2_000) { while (emissions.size < 2) delay(10) }
        job.cancelAndJoin()

        assertThat(emissions).containsExactly(30, 50)
        assertThat(withTimeout(2_000) { facade.current(dataStore, pageSize) }).isEqualTo(50)
    }

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var upstreamSubscriptions = 0
            private set

        override val data: Flow<Preferences> = flow {
            upstreamSubscriptions++
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("read failed") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("read failed")
    }

    /** Emits once, then suspends the shared collection until [release]. */
    private class PausingDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val startedReading = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        val started: CompletableDeferred<Unit> get() = startedReading

        override val data: Flow<Preferences> = flow {
            emit(state.value)
            startedReading.complete(Unit)
            released.await()
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }

        fun release() {
            released.complete(Unit)
        }
    }
}
