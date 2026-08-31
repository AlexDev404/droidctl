package dev.alexdev404.droidctl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.model.QualityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "droidctl")

/** User settings that shape a mirroring session. */
data class MirrorSettings(
    /**
     * Bit rate and resolution together, as one choice.
     *
     * They are not offered separately: asking the Target for 8 Mbps at 25% of
     * its resolution, or 256 kbps at full resolution, is never what anyone
     * wants, and two independent controls make those combinations the easiest
     * ones to reach by accident.
     */
    val qualityMode: QualityMode = QualityMode.Automatic,
    val maxFps: Int = 0,
    val stayAwake: Boolean = false,
    val showTouches: Boolean = false,
    /**
     * Blank the Target's own screen while mirroring.
     *
     * Nothing needs undoing on teardown: the server's CleanUp restores the
     * display when it exits, including when the Host disappears abruptly.
     */
    val turnScreenOff: Boolean = false,
    /** Dump the raw post-header payload stream to a file instead of decoding it. */
    val rawDumpEnabled: Boolean = false,
    /** Debug builds only: route the connection layer at the bundled fake server. */
    val useFakeServer: Boolean = false,
)

/**
 * Persistence, on `DataStore<Preferences>`.
 *
 * Known targets are stored as a set of JSON objects rather than in a database:
 * the list is a handful of entries the user curates by hand, and a schema for
 * it would cost more than it is worth.
 */
class DroidCtlPreferences(private val context: Context) {

    private val log = DroidCtlLog.session

    val knownTargets: Flow<List<KnownTarget>> = context.dataStore.data.map { prefs ->
        (prefs[KEY_KNOWN_TARGETS] ?: emptySet())
            .mapNotNull { decodeTarget(it) }
            .sortedByDescending { it.lastConnectedAtMillis }
            // The stored set is keyed by the whole JSON blob, so two entries for
            // one Target that differ only in name or timestamp would both
            // survive. rememberTarget already prevents that, but the list is
            // rendered with the serial as a list key and a duplicate there is a
            // crash rather than a cosmetic problem. Newest wins.
            .distinctBy { it.serial }
    }

    val settings: Flow<MirrorSettings> = context.dataStore.data.map { prefs ->
        MirrorSettings(
            qualityMode = QualityMode.decode(prefs[KEY_QUALITY_MODE]),
            maxFps = prefs[KEY_MAX_FPS] ?: 0,
            stayAwake = prefs[KEY_STAY_AWAKE] ?: false,
            showTouches = prefs[KEY_SHOW_TOUCHES] ?: false,
            turnScreenOff = prefs[KEY_TURN_SCREEN_OFF] ?: false,
            rawDumpEnabled = prefs[KEY_RAW_DUMP] ?: false,
            useFakeServer = prefs[KEY_FAKE_SERVER] ?: false,
        )
    }

    /** The last `host:port` typed into the manual connect field. */
    val lastManualConnect: Flow<String> =
        context.dataStore.data.map { it[KEY_LAST_CONNECT] ?: "" }

    /** The last `host:port` typed into the manual pairing field. */
    val lastManualPair: Flow<String> =
        context.dataStore.data.map { it[KEY_LAST_PAIR] ?: "" }

    suspend fun rememberTarget(target: KnownTarget) {
        context.dataStore.edit { prefs ->
            val stored = (prefs[KEY_KNOWN_TARGETS] ?: emptySet()).mapNotNull { decodeTarget(it) }
            val previous = stored.firstOrNull { it.host == target.host && it.port == target.port }
            val others = stored.filterNot { it.host == target.host && it.port == target.port }
            // Reconnecting must not throw away what an earlier session measured.
            val merged = target.copy(
                lastMeasuredBitsPerSecond =
                    target.lastMeasuredBitsPerSecond ?: previous?.lastMeasuredBitsPerSecond,
            )
            prefs[KEY_KNOWN_TARGETS] = (others + merged).map { encodeTarget(it) }.toSet()
        }
    }

    suspend fun forgetTarget(target: KnownTarget) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KNOWN_TARGETS] = (prefs[KEY_KNOWN_TARGETS] ?: emptySet())
                .mapNotNull { decodeTarget(it) }
                .filterNot { it.host == target.host && it.port == target.port }
                .map { encodeTarget(it) }
                .toSet()
        }
    }

    /**
     * Records what the last push to this Target measured, so Automatic still has
     * a figure on later sessions when the push is skipped.
     */
    suspend fun rememberBandwidth(target: KnownTarget, bitsPerSecond: Long) {
        context.dataStore.edit { prefs ->
            val entries = (prefs[KEY_KNOWN_TARGETS] ?: emptySet()).mapNotNull { decodeTarget(it) }
            val existing = entries.firstOrNull { it.host == target.host && it.port == target.port }
                ?: return@edit
            prefs[KEY_KNOWN_TARGETS] =
                (entries.filterNot { it.host == target.host && it.port == target.port } +
                    existing.copy(lastMeasuredBitsPerSecond = bitsPerSecond))
                    .map { encodeTarget(it) }
                    .toSet()
        }
    }

    suspend fun setLastManualConnect(value: String) {
        context.dataStore.edit { it[KEY_LAST_CONNECT] = value }
    }

    suspend fun setLastManualPair(value: String) {
        context.dataStore.edit { it[KEY_LAST_PAIR] = value }
    }

    suspend fun updateSettings(transform: (MirrorSettings) -> MirrorSettings) {
        val current = settings.first()
        val next = transform(current)
        context.dataStore.edit { prefs ->
            prefs[KEY_QUALITY_MODE] = next.qualityMode.encode()
            prefs[KEY_MAX_FPS] = next.maxFps
            prefs[KEY_STAY_AWAKE] = next.stayAwake
            prefs[KEY_SHOW_TOUCHES] = next.showTouches
            prefs[KEY_TURN_SCREEN_OFF] = next.turnScreenOff
            prefs[KEY_RAW_DUMP] = next.rawDumpEnabled
            prefs[KEY_FAKE_SERVER] = next.useFakeServer
        }
    }

    // --- Stale adb forwards -------------------------------------------------
    //
    // A forward that outlives its session sits in the adb server until adb is
    // restarted, and the next session that happens to pick the same port fails
    // in a way that looks like the Target refusing the connection. The app
    // records every forward it creates so it can clear the leftovers at start.

    /** Records a forward this app created, so it can be cleaned up after a crash. */
    suspend fun recordForward(serial: String, hostPort: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_FORWARDS] = (prefs[KEY_ACTIVE_FORWARDS] ?: emptySet()) + "$serial|$hostPort"
        }
    }

    suspend fun clearForwardRecord(serial: String, hostPort: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_FORWARDS] = (prefs[KEY_ACTIVE_FORWARDS] ?: emptySet()) - "$serial|$hostPort"
        }
    }

    /** Every forward this app is known to have created and not yet removed. */
    suspend fun recordedForwards(): List<Pair<String, Int>> =
        context.dataStore.data.first()[KEY_ACTIVE_FORWARDS].orEmpty().mapNotNull { entry ->
            val parts = entry.split('|')
            val port = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            parts[0] to port
        }

    suspend fun clearAllForwardRecords() {
        context.dataStore.edit { it[KEY_ACTIVE_FORWARDS] = emptySet() }
    }

    private fun encodeTarget(target: KnownTarget): String = JSONObject().apply {
        put("name", target.name)
        put("host", target.host)
        put("port", target.port)
        put("lastConnectedAt", target.lastConnectedAtMillis)
        target.lastMeasuredBitsPerSecond?.let { put("lastMeasuredBps", it) }
    }.toString()

    private fun decodeTarget(raw: String): KnownTarget? = runCatching {
        val json = JSONObject(raw)
        KnownTarget(
            name = json.optString("name").ifBlank { json.getString("host") },
            host = json.getString("host"),
            port = json.getInt("port"),
            lastConnectedAtMillis = json.optLong("lastConnectedAt"),
            lastMeasuredBitsPerSecond = json.optLong("lastMeasuredBps").takeIf { it > 0 },
        )
    }.onFailure { log.w("Discarding an unreadable saved Target", it) }.getOrNull()

    private companion object {
        val KEY_KNOWN_TARGETS = stringSetPreferencesKey("known_targets")
        val KEY_ACTIVE_FORWARDS = stringSetPreferencesKey("active_forwards")
        val KEY_LAST_CONNECT = stringPreferencesKey("last_manual_connect")
        val KEY_LAST_PAIR = stringPreferencesKey("last_manual_pair")
        val KEY_QUALITY_MODE = stringPreferencesKey("quality_mode")
        val KEY_MAX_FPS = intPreferencesKey("max_fps")
        val KEY_STAY_AWAKE = booleanPreferencesKey("stay_awake")
        val KEY_SHOW_TOUCHES = booleanPreferencesKey("show_touches")
        val KEY_TURN_SCREEN_OFF = booleanPreferencesKey("turn_screen_off")
        val KEY_RAW_DUMP = booleanPreferencesKey("raw_dump")
        val KEY_FAKE_SERVER = booleanPreferencesKey("use_fake_server")
    }
}
