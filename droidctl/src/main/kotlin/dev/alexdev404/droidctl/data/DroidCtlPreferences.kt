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
import dev.alexdev404.droidctl.scrcpy.ScrcpyOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "droidctl")

/** User settings that shape a mirroring session. */
data class MirrorSettings(
    val maxSize: Int = 0,
    val videoBitRate: Int = ScrcpyOptions.DEFAULT_VIDEO_BIT_RATE,
    val maxFps: Int = 0,
    val stayAwake: Boolean = false,
    val showTouches: Boolean = false,
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
            maxSize = prefs[KEY_MAX_SIZE] ?: 0,
            videoBitRate = prefs[KEY_BIT_RATE] ?: ScrcpyOptions.DEFAULT_VIDEO_BIT_RATE,
            maxFps = prefs[KEY_MAX_FPS] ?: 0,
            stayAwake = prefs[KEY_STAY_AWAKE] ?: false,
            showTouches = prefs[KEY_SHOW_TOUCHES] ?: false,
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
            val existing = (prefs[KEY_KNOWN_TARGETS] ?: emptySet())
                .mapNotNull { decodeTarget(it) }
                .filterNot { it.host == target.host && it.port == target.port }
            prefs[KEY_KNOWN_TARGETS] = (existing + target).map { encodeTarget(it) }.toSet()
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
            prefs[KEY_MAX_SIZE] = next.maxSize
            prefs[KEY_BIT_RATE] = next.videoBitRate
            prefs[KEY_MAX_FPS] = next.maxFps
            prefs[KEY_STAY_AWAKE] = next.stayAwake
            prefs[KEY_SHOW_TOUCHES] = next.showTouches
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
    }.toString()

    private fun decodeTarget(raw: String): KnownTarget? = runCatching {
        val json = JSONObject(raw)
        KnownTarget(
            name = json.optString("name").ifBlank { json.getString("host") },
            host = json.getString("host"),
            port = json.getInt("port"),
            lastConnectedAtMillis = json.optLong("lastConnectedAt"),
        )
    }.onFailure { log.w("Discarding an unreadable saved Target", it) }.getOrNull()

    private companion object {
        val KEY_KNOWN_TARGETS = stringSetPreferencesKey("known_targets")
        val KEY_ACTIVE_FORWARDS = stringSetPreferencesKey("active_forwards")
        val KEY_LAST_CONNECT = stringPreferencesKey("last_manual_connect")
        val KEY_LAST_PAIR = stringPreferencesKey("last_manual_pair")
        val KEY_MAX_SIZE = intPreferencesKey("max_size")
        val KEY_BIT_RATE = intPreferencesKey("video_bit_rate")
        val KEY_MAX_FPS = intPreferencesKey("max_fps")
        val KEY_STAY_AWAKE = booleanPreferencesKey("stay_awake")
        val KEY_SHOW_TOUCHES = booleanPreferencesKey("show_touches")
        val KEY_RAW_DUMP = booleanPreferencesKey("raw_dump")
        val KEY_FAKE_SERVER = booleanPreferencesKey("use_fake_server")
    }
}
