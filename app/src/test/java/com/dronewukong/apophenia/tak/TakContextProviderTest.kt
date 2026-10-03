package com.dronewukong.apophenia.tak

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TakContextProviderTest {
    private lateinit var context: Context
    private val hasher = DeviceIdentifierHasher.withFixedKey(ByteArray(32) { 7 })
    private val ownHash = hasher.hash(DeviceIdentifierKind.TAK_UID, "own-asset")
    private val ownXml = cot("own-asset", "OWN-CALLSIGN", 41.1)
    private val otherXml = cot("other-asset", "OTHER-CALLSIGN", 42.2)

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); HardwareGates.clearAuthorizationsForTests(context) }
    @After fun tearDown() { HardwareGates.clearAuthorizationsForTests(context) }

    @Test fun defaultFilterKeepsOwnTrackAndPersistsNoUidOrCallsign() {
        val own = TakSnapshotParser.parse(ownXml, ownHash, false, 5, false, hasher, 2_000)
        val other = TakSnapshotParser.parse(otherXml, ownHash, false, 5, false, hasher, 2_000)
        assertTrue(own.any { it.metric == "tak_latitude_deg" && it.value == 41.1 })
        assertTrue(other.isEmpty())
        val storedText = own.joinToString()
        assertFalse(storedText.contains("own-asset"))
        assertFalse(storedText.contains("OWN-CALLSIGN"))
        assertTrue(own.all { it.metadata.contains("scope=own_asset") })
    }

    @Test fun fullGateParserLabelsTrafficVisibleOnConnectionAndHashesUid() {
        val rows = TakSnapshotParser.parse(otherXml, ownHash, true, null, true, hasher, 2_000)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.source == "tak_visible" && it.metadata.contains("visible_on_your_connection") })
        assertFalse(rows.joinToString().contains("other-asset"))
        assertEquals(42.2, rows.single { it.metric == "tak_latitude_deg" }.value, 0.0)
    }

    @Test fun providerRequiresBaseGateAndFullTrafficRequiresSeparateTierThreeGate() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        assertTrue(TakContextProvider(context).collect(1, false).isEmpty())
        HardwareGates.setAuthorized(context, HardwareGates.Gate.LIVE_TAK_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation)
        assertEquals(1, TakContextProvider(context).collect(1, false).map { it.source }.distinct().size)
        assertTrue(TakContextProvider(context).collect(1, false).all { it.source == "tak_own" })
        HardwareGates.setAuthorized(context, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL, true, HardwareGates.ConsentProof.CapabilityConditionalConfirmation)
        assertTrue(TakContextProvider(context).collect(1, false).any { it.source == "tak_visible" })
    }

    @Test fun settingsPersistOnlyOwnUidHashAndParserRejectsDoctype() {
        TakSettings.save(context, "never-store-this-raw-uid", "239.2.3.1", 6969, hasher)
        val prefsText = context.getSharedPreferences("tak_context", Context.MODE_PRIVATE).all.toString()
        assertTrue(TakSettings.ownUidConfigured(context))
        assertFalse(prefsText.contains("never-store-this-raw-uid"))
        val malicious = """<!DOCTYPE event [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><event uid="own-asset" type="a-f"><point lat="1" lon="2"/></event>"""
        assertTrue(TakSnapshotParser.parse(malicious, ownHash, true, null, false, hasher).isEmpty())
    }

    private fun cot(uid: String, callsign: String, lat: Double) = """<?xml version="1.0"?><event version="2.0" uid="$uid" type="a-f-A-M-H-Q" time="1970-01-01T00:00:01.000Z" start="1970-01-01T00:00:01.000Z" stale="1970-01-01T00:01:00.000Z" how="m-g"><point lat="$lat" lon="-87.6" hae="100" ce="5" le="8"/><detail><contact callsign="$callsign"/><track course="90" speed="10"/></detail></event>"""
}
