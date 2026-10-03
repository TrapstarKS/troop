package com.noop.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.noop.DemoRuntimePolicy
import com.noop.NoopApplication
import com.noop.data.DeviceRegistry
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.oura.OuraRingGen
import com.noop.ui.AppViewModel
import com.noop.ui.MainActivity
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DemoBluetoothRuntimeTest {
    private class RadioContext(base: Context) : ContextWrapper(base) {
        var bluetoothLookups = 0
        var bluetoothReceivers = 0
        var serviceStarts = 0
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.BLUETOOTH_SERVICE) {
                bluetoothLookups++
                return null // The allowed branch is observable without a real adapter.
            }
            return super.getSystemService(name)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
            if (filter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED)) bluetoothReceivers++
            return super.registerReceiver(receiver, filter, flags)
        }
        override fun startForegroundService(service: Intent): ComponentName {
            serviceStarts++
            return ComponentName(packageName, WhoopConnectionService::class.java.name)
        }
        override fun startService(service: Intent): ComponentName {
            serviceStarts++
            return ComponentName(packageName, WhoopConnectionService::class.java.name)
        }
    }

    @After fun closeSingleton() = WhoopDatabase.close()

    @Test fun actualDemoConstructorsAndAllConnectEntrypointsRemainInert() {
        val context = RadioContext(RuntimeEnvironment.getApplication())
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        val policy = DemoRuntimePolicy(true)
        val client = WhoopBleClient(context, repository = WhoopRepository(db), runtimePolicy = policy)
        val standard = StandardHrSource(context, "demo-hr", { _, _ -> }, { _, _, _ -> }, runtimePolicy = policy)
        val ftms = FtmsSource(context, {}, runtimePolicy = policy)
        val huami = HuamiHrSource(context, "demo-huami", {}, runtimePolicy = policy)
        val oura = OuraLiveSource(context, "demo-oura", OuraRingGen.GEN3, { _, _ -> }, { null }, runtimePolicy = policy)
        val broadcaster = HrBroadcaster(context, runtimePolicy = policy)
        try {
            client.connect()
            client.connectFromSystem()
            client.reconnectToAddress("00:11:22:33:44:55", WhoopModel.WHOOP4)
            client.onBluetoothRadioOn()
            client.scanForWhoops(WhoopModel.WHOOP4)
            client.scanForWhoops(WhoopModel.WHOOP5_MG)
            client.stopWhoopScan()
            listOf(standard, ftms, huami, oura).forEach { it.scan(); it.connect("00:11:22:33:44:55"); it.stop() }
            oura.reconnect()
            broadcaster.start()
            broadcaster.update(75)
            broadcaster.stop()
            assertEquals(0, context.bluetoothLookups)
            assertEquals(0, context.bluetoothReceivers)
            assertEquals(0, context.serviceStarts)
            assertFalse(client.state.value.scanning)
            assertFalse(client.state.value.connected)
            assertNull(client.state.value.statusNote)
            assertFalse(standard.scanning.value)
            assertFalse(ftms.scanning.value)
            assertFalse(huami.scanning.value)
            assertFalse(oura.scanning.value)
            assertEquals(OuraLiveSource.LinkPhase.DISCONNECTED, oura.linkPhase.value)
            assertFalse(broadcaster.advertising.value)
            assertNull(broadcaster.statusNote.value)
        } finally {
            client.shutdown()
            db.close()
        }
    }

    @Test fun actualFullConstructorsStillResolveTheBluetoothService() {
        val context = RadioContext(RuntimeEnvironment.getApplication())
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        val policy = DemoRuntimePolicy(false)
        val client = WhoopBleClient(context, repository = WhoopRepository(db), runtimePolicy = policy)
        try {
            StandardHrSource(context, "full-hr", { _, _ -> }, { _, _, _ -> }, runtimePolicy = policy)
            FtmsSource(context, {}, runtimePolicy = policy)
            HuamiHrSource(context, "full-huami", {}, runtimePolicy = policy)
            OuraLiveSource(context, "full-oura", OuraRingGen.GEN3, { _, _ -> }, { null }, runtimePolicy = policy)
            HrBroadcaster(context, runtimePolicy = policy)
            assertEquals(6, context.bluetoothLookups)
        } finally {
            client.shutdown()
            db.close()
        }
    }

    @Test fun demoCoordinatorPublishesStorageSelectionWithoutCreatingAnyLiveDriver() = runBlocking {
        val context = RadioContext(RuntimeEnvironment.getApplication())
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val registry = DeviceRegistry(db)
            val rows = sourceRows()
            rows.forEach { registry.add(it) }
            var creations = 0
            var whoopActions = 0
            val coordinator = SourceCoordinator(
                context, registry, WhoopRepository(db), { _, _ -> }, { whoopActions++ }, { whoopActions++ },
                scope = CoroutineScope(Dispatchers.Unconfined),
                runtimePolicy = DemoRuntimePolicy(true),
                sourceFactory = { _, _ -> creations++; error("Demo must not construct a live source") },
            )
            rows.forEach {
                assertTrue(coordinator.reconcileActiveDevice(it.id))
                assertEquals(it.id, coordinator.activeDeviceId.value)
            }
            assertTrue(coordinator.reconcileActiveDevice(WhoopBleClient.DEFAULT_DEVICE_ID))
            assertEquals(0, creations)
            assertEquals(0, whoopActions)
            assertEquals(rows, registry.all())
            assertEquals(0, context.bluetoothLookups)
        } finally { db.close() }
    }

    @Test fun fullCoordinatorStillCreatesConnectsAndStopsItsSources() = runBlocking {
        val context = RadioContext(RuntimeEnvironment.getApplication())
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val registry = DeviceRegistry(db)
            val rows = sourceRows()
            rows.forEach { registry.add(it) }
            val created = arrayListOf<String>()
            val connected = arrayListOf<String>()
            var stopped = 0
            var whoopStarts = 0
            var whoopStops = 0
            val coordinator = SourceCoordinator(
                context, registry, WhoopRepository(db), { _, _ -> }, { whoopStarts++ }, { whoopStops++ },
                scope = CoroutineScope(Dispatchers.Unconfined), runtimePolicy = DemoRuntimePolicy(false),
                sourceFactory = { id, _ ->
                    created.add(id)
                    object : LiveHrSource {
                        override fun scan() { fail("Stored addresses must connect directly") }
                        override fun connect(address: String) { connected.add(address) }
                        override fun stop() { stopped++ }
                    }
                },
            )
            rows.forEach { assertTrue(coordinator.reconcileActiveDevice(it.id)) }
            assertTrue(coordinator.reconcileActiveDevice(WhoopBleClient.DEFAULT_DEVICE_ID))
            assertEquals(rows.map { it.id }, created)
            assertEquals(rows.map { it.peripheralId }, connected)
            assertEquals(4, stopped)
            assertEquals(1, whoopStarts)
            assertEquals(1, whoopStops)
        } finally { db.close() }
    }

    @Test fun actualDemoAppStartupIgnoresPersistedReconnectAndBroadcastWithoutChangingThem() {
        val context = RadioContext(RuntimeEnvironment.getApplication())
        NoopPrefs.setLastDevice(context, "00:11:22:33:44:55", WhoopModel.WHOOP4)
        NoopPrefs.setBackgroundConnection(context, true)
        NoopPrefs.setHrBroadcast(context, true)
        val app = NoopApplication(DemoRuntimePolicy(true))
        ReflectionHelpers.callInstanceMethod<Unit>(
            app, "attachBaseContext", ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
        val store = ViewModelStore()
        val vm = AppViewModel(app)
        store.put("demo", vm)
        try {
            vm.connect()
            vm.promoteBackgroundConnectionIfActive()
            val scanners = listOf(vm.makeStrapScanner(), vm.makeFtmsScanner(), vm.makeHuamiScanner(), vm.makeOuraScanner())
            scanners.forEach { it.scan(); it.stop() }
            assertEquals(0, context.bluetoothLookups)
            assertEquals(0, context.bluetoothReceivers)
            assertEquals(0, context.serviceStarts)
            assertFalse(vm.hrBroadcastAdvertising.value)
            assertTrue(vm.hrBroadcast.value)
            assertEquals("00:11:22:33:44:55" to WhoopModel.WHOOP4, NoopPrefs.lastDevice(context))
            assertTrue(NoopPrefs.backgroundConnection(context))
            assertTrue(NoopPrefs.hrBroadcast(context))
        } finally {
            store.clear()
            vm.ble.shutdown()
        }
    }

    @Test fun actualPermissionEntrypointSkipsDemoAndRetainsFullRequests() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val requests = arrayListOf<List<String>>()
        activity.requestBlePermissions(DemoRuntimePolicy(true)) { requests.add(it.toList()) }
        assertTrue(requests.isEmpty())
        activity.requestBlePermissions(DemoRuntimePolicy(false)) { requests.add(it.toList()) }
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS), requests.single())
    }

    private fun sourceRows() = listOf(SourceKind.liveBLE, SourceKind.ftms, SourceKind.huami, SourceKind.oura)
        .mapIndexed { index, kind ->
            PairedDeviceRow(
                id = "demo-$index", brand = "Demo", model = "Demo", nickname = null,
                sourceKind = kind.name, capabilities = "hr", status = "paired",
                addedAt = index.toLong(), lastSeenAt = index.toLong(), peripheralId = "00:11:22:33:44:0$index",
            )
        }
}
