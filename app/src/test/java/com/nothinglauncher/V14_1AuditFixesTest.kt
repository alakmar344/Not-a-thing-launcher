package com.nothinglauncher

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.nothinglauncher.drawer.HiddenVaultSheet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V14_1AuditFixesTest {

    private lateinit var context: Context
    private lateinit var prefs: NothingPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = NothingPrefs.get(context)
        FocusSoundEngine.stop()
    }

    @After
    fun tearDown() {
        FocusSoundEngine.stop()
    }

    @Test
    fun hiddenVaultAuthPathRequiresAuthAcrossApiLevelsWhenDeviceSecure() {
        assertEquals(
            HiddenVaultSheet.AuthPath.ALLOW_DIRECT,
            HiddenVaultSheet.determineAuthPath(0, isDeviceSecure = true, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertEquals(
            HiddenVaultSheet.AuthPath.ALLOW_DIRECT,
            HiddenVaultSheet.determineAuthPath(3, isDeviceSecure = false, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertEquals(
            HiddenVaultSheet.AuthPath.REQUIRE_CREDENTIAL_FALLBACK,
            HiddenVaultSheet.determineAuthPath(2, isDeviceSecure = true, sdkInt = Build.VERSION_CODES.O)
        )
        assertEquals(
            HiddenVaultSheet.AuthPath.REQUIRE_CREDENTIAL_FALLBACK,
            HiddenVaultSheet.determineAuthPath(2, isDeviceSecure = true, sdkInt = Build.VERSION_CODES.P)
        )
        assertEquals(
            HiddenVaultSheet.AuthPath.REQUIRE_BIOMETRIC_OR_CREDENTIAL,
            HiddenVaultSheet.determineAuthPath(2, isDeviceSecure = true, sdkInt = Build.VERSION_CODES.Q)
        )
    }

    @Test
    fun kineticMatrixSnakePreventsRapidDoubleTapNeckReversal() {
        val matrix = KineticMatrixView(context)
        matrix.mode = KineticMatrixView.Mode.SNAKE
        matrix.resetSnake()
        assertEquals(1, matrix.snakeDirX)
        assertEquals(0, matrix.snakeDirY)

        // Rapid double-tap within one 210 ms tick: Up then Left before stepSnake() runs.
        matrix.steerSnake(0, -1)
        matrix.steerSnake(-1, 0)
        // Must ignore 180-degree reversal against committed Right (1, 0) direction.
        assertEquals(0, matrix.snakeDirX)
        assertEquals(-1, matrix.snakeDirY)

        matrix.stepSnake()
        assertFalse(matrix.snakeGameOver)
    }

    @Test
    fun focusSoundEngineHandlesRapidProfileCyclingWithoutLeakingState() {
        FocusSoundEngine.setProfile(FocusSoundEngine.Profile.BROWN)
        assertEquals(FocusSoundEngine.Profile.BROWN, FocusSoundEngine.current)
        FocusSoundEngine.stop()
        assertEquals(FocusSoundEngine.Profile.OFF, FocusSoundEngine.current)
        FocusSoundEngine.setProfile(FocusSoundEngine.Profile.RAIN)
        FocusSoundEngine.setProfile(FocusSoundEngine.Profile.GAMMA_40HZ)
        assertEquals(FocusSoundEngine.Profile.GAMMA_40HZ, FocusSoundEngine.current)
        FocusSoundEngine.stop()
        assertFalse(FocusSoundEngine.isPlaying())
    }

    @Test
    fun nothingPrefsImportValidatesBoundsPackagesAndTimezones() {
        val originalZone = "UTC"
        prefs.worldClockZoneId = originalZone

        val oversized = "{${"a".repeat(600_000)}}"
        assertFalse(prefs.importFromJson(oversized))

        val payload = JSONObject().apply {
            put("version", 14)
            put("worldClockZoneId", "Not/A_Real_Zone")
            put("weatherCity", "Tokyo" + "X".repeat(200))
            put("deskMode", 99)
            put("kineticMode", -5)
            put("habitDates", JSONArray(listOf("2026-11-27", "bad-date", "2026-11-26")))
            put("hiddenPackages", JSONArray(listOf("com.example.valid", "../evil path", "bad pkg")))
        }.toString()

        assertTrue(prefs.importFromJson(payload))
        assertEquals(originalZone, prefs.worldClockZoneId)
        assertEquals(80, prefs.weatherCity.length)
        assertEquals(3, prefs.deskMode)
        assertEquals(0, prefs.kineticMode)
        assertEquals(setOf("2026-11-27", "2026-11-26"), prefs.habitDates)
        assertEquals(setOf("com.example.valid"), prefs.hiddenPackages)

        val validZonePayload = JSONObject().apply {
            put("version", 14)
            put("worldClockZoneId", "Asia/Tokyo")
        }.toString()
        assertTrue(prefs.importFromJson(validZonePayload))
        assertEquals("Asia/Tokyo", prefs.worldClockZoneId)
    }

    @Test
    fun weatherRepositoryMatchesHourlyIndexToLocationTimezone() {
        val times = JSONArray().apply {
            for (h in 0 until 24) {
                put(String.format("2026-11-27T%02d:00", h))
            }
        }
        val json = JSONObject().apply {
            put("current", JSONObject().apply { put("time", "2026-11-27T18:15") })
            put("hourly", JSONObject().apply { put("time", times) })
        }
        assertEquals(18, WeatherRepository.resolveHourlyStartIndex(json, fallbackHour = 2))
    }

    @Test
    fun wordClockFormatterNeverProducesDeadNearBranch() {
        for (min in 0..59) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, min)
            }
            val (line1, _) = WordClockFormatter.format(cal)
            assertFalse("Unexpected NEAR in $line1 at minute $min", line1.startsWith("NEAR"))
        }
    }

    @Test
    fun notificationDigestClearedWhenPermissionRevoked() {
        val snap = NotificationDigestRepository.current(context)
        assertFalse(snap.permissionGranted)
        assertTrue(snap.entries.isEmpty())
    }

    @Test
    fun userFacingStringsScrubbedAndBumpedToV14_1() {
        val resIds = listOf(
            R.string.accent_red,
            R.string.nt_icon_mono,
            R.string.nt_icon_dots,
            R.string.nt_dream_label,
            R.string.nt_desk_title,
            R.string.nt_desk_mode_glyph,
            R.string.essential_title,
            R.string.element_folder,
            R.string.element_note,
            R.string.nt_digest_title,
            R.string.media_artist_default,
            R.string.media_title_default
        )
        val forbidden = listOf("Nothing Red", "Nothing Monochrome", "Nothing StandBy", "Glyph", "NOTHING SOUNDS", "Essential")
        resIds.forEach { id ->
            val s = context.getString(id)
            forbidden.forEach { bad ->
                assertFalse("String '$s' unexpectedly contains '$bad'", s.contains(bad, ignoreCase = true))
            }
        }
        assertTrue(context.getString(R.string.nt_entry_title_v14).contains("14.1"))
        assertNotEquals(0, R.drawable.ic_launcher_foreground)
        assertNotEquals(0, R.drawable.ic_launcher_monochrome)
    }
}
