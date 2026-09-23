package com.eona.app.feature.drive.group

import androidx.compose.ui.graphics.Color
import com.eona.app.core.model.GeoPoint

/**
 * Another member of the group as the main map knows them: who, and in which colour. Where they are
 * comes separately, as a string of timed positions ([GroupSample]).
 */
data class GroupMapMember(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    /** Their colour in the group, the same in every list and on every map. */
    val colorIndex: Int,
)

/** One position of another member, timed on the server's clock (seconds since 1970). */
data class GroupSample(
    val at: Double,
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    val speedMps: Double,
)

/** Another member's route on the main map: its version and its colour. */
data class GroupMapRoute(val rev: Int, val colorIndex: Int, val points: List<GeoPoint>)

/**
 * What the main map shows of the group.
 *
 * Positions arrive as they are sent (the group stream), each timed on the server's clock. The map
 * never guesses ahead: it shows every member a few seconds in the past, between two positions it
 * really received, along that member's own route — the way a video plays from its buffer. The
 * movement is as smooth as the driver's own arrow, and it never goes back on itself.
 *
 * Written by the group session and read by the map's frame loop, both on the main thread; never
 * observed by Compose. [version] tells the map when the members, the routes or the focus changed.
 */
class GroupMapLayer {
    var version = 0
        private set
    var members: List<GroupMapMember> = emptyList()
        private set

    /** Each member's last positions, oldest first. */
    private val samplesById = HashMap<String, List<GroupSample>>()
    val samples: Map<String, List<GroupSample>> get() = samplesById

    /** The others' routes, by member. */
    var routes: Map<String, GroupMapRoute> = emptyMap()
        private set

    /** The member the camera follows while following; null = the driver. */
    var focus: String? = null
        private set

    /** Raised to ask the map for a view of everyone at once. */
    var overviewRequest = 0
        private set

    /** The server's clock minus this phone's, in seconds; null until the server has spoken. */
    private var clockOffset: Double? = null

    /** Now, on the server's clock (seconds). */
    val serverNow: Double get() = System.currentTimeMillis() / 1000.0 + (clockOffset ?: 0.0)

    /**
     * The server said what time it was. What arrives is always a little late (the trip over the
     * network), so the reading that makes the server look furthest ahead wins, and a lower one is
     * only eased in slowly — the phone's clock may drift over hours.
     */
    fun noteServerTime(serverNowMs: Long?) {
        serverNowMs ?: return
        val reading = (serverNowMs - System.currentTimeMillis()) / 1000.0
        val current = clockOffset
        clockOffset = when {
            current == null -> reading
            reading > current -> reading
            else -> current + (reading - current) * 0.05
        }
    }

    fun setMembers(fresh: List<GroupMapMember>) {
        if (fresh == members) return
        members = fresh
        val ids = fresh.map { it.id }.toSet()
        samplesById.keys.retainAll(ids)
        version += 1
    }

    /** One more position of [memberId]; an older or repeated one is ignored. */
    fun addSample(memberId: String, sample: GroupSample) {
        val list = samplesById[memberId].orEmpty()
        val last = list.lastOrNull()
        if (last != null && sample.at <= last.at) return
        samplesById[memberId] = (list + sample).takeLast(KEPT_SAMPLES)
    }

    fun setRoute(memberId: String, route: GroupMapRoute) {
        routes = routes + (memberId to route)
        version += 1
    }

    /** Only these members keep a route on the map. */
    fun keepRoutes(ids: Set<String>) {
        if (routes.keys.all { it in ids }) return
        routes = routes.filterKeys { it in ids }
        version += 1
    }

    fun setFocus(id: String?) {
        if (id == focus) return
        focus = id
        version += 1
    }

    fun requestOverview() {
        overviewRequest += 1
        version += 1
    }

    fun clear() {
        if (members.isEmpty() && routes.isEmpty() && focus == null && samplesById.isEmpty()) return
        members = emptyList()
        samplesById.clear()
        routes = emptyMap()
        focus = null
        version += 1
    }

    private companion object {
        const val KEPT_SAMPLES = 12
    }
}

/** One colour per driver, in the order they joined. Five of them, as a group holds five. */
object GroupPalette {
    val argb = intArrayOf(
        0xFF2BD4DE.toInt(),
        0xFFFABD4A.toInt(),
        0xFF8CC759.toInt(),
        0xFFF2738C.toInt(),
        0xFF9E8FF2.toInt(),
    )

    fun argb(index: Int): Int = argb[((index % argb.size) + argb.size) % argb.size]

    fun color(index: Int): Color = Color(argb(index))
}
