package com.eona.app.data.trips

import com.eona.app.BuildConfig
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Where a driver of the group stands right now. */
enum class GroupMemberState(val wire: String, val label: String) {
    /** In the group, not on the road yet. */
    Invited("invited", "Pas encore parti"),
    Driving("driving", "En route"),
    Arrived("arrived", "Arrivé"),

    /** Stepped out of the group before arriving. */
    Left("left", "A quitté le trajet");

    companion object {
        fun fromWire(value: String?): GroupMemberState = entries.firstOrNull { it.wire == value } ?: Invited
    }
}

/**
 * One driver of the group, as the others are allowed to see them. Someone who stopped sharing
 * gives their name and their state, and nothing else: no position, no speed, no progress.
 */
data class GroupMember(
    val id: String,
    val name: String,
    val state: GroupMemberState,
    val sharing: Boolean,
    /** Heard from in the last moments; false means "signal perdu", last position kept. */
    val online: Boolean,
    val rank: Int?,
    val position: GeoPoint?,
    val bearing: Double?,
    val speedKmh: Int?,
    /** 0 to 1 along their own route. */
    val progress: Double,
    val remainingMeters: Int?,
    val etaAtMs: Long?,
    /** Their time and distance, once they are there. */
    val durationSeconds: Int?,
    val distanceMeters: Int?,
    /** Their profile picture, when they set one. */
    val avatarUrl: String?,
    /** When their position was taken (server clock, epoch millis). */
    val positionAtMs: Long?,
    /** The version of their route: the map asks for it again only when it changes. */
    val routeRev: Int,
) {
    /** On the map and in the lists: still in the group. */
    val isPresent: Boolean get() = state != GroupMemberState.Left

    /** "112 km/h · 34 km", "2e · 1 h 05", "Ne partage pas sa position". */
    val detailLabel: String
        get() {
            if (!sharing) return "Ne partage pas sa position"
            if (state == GroupMemberState.Arrived) {
                return listOfNotNull(rank?.let { if (it == 1) "1er" else "${it}e" }, durationSeconds?.let(::groupDuration))
                    .joinToString(" · ")
            }
            if (state == GroupMemberState.Left || state == GroupMemberState.Invited) return state.label
            if (!online) return "Signal perdu"
            val parts = listOfNotNull(speedKmh?.let { "$it km/h" }, remainingMeters?.let(::groupDistance))
            return if (parts.isEmpty()) state.label else parts.joinToString(" · ")
        }
}

/** "850 m", "34 km". */
fun groupDistance(meters: Int): String = if (meters < 1000) "$meters m" else "${Math.round(meters / 1000.0)} km"

/** "45 min", "1 h 05". */
fun groupDuration(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
}

/** One member's route, as drawn on the main map. */
data class GroupRoute(val memberId: String, val rev: Int, val points: List<GeoPoint>)

/** One line of the arrival ranking, frozen when the trip ends. */
data class GroupRankEntry(
    val id: String,
    val name: String,
    /** null for someone who never arrived. */
    val rank: Int?,
    val state: GroupMemberState,
    val durationSeconds: Int?,
    val distanceMeters: Int?,
) {
    /** "1er", "3e", "—". */
    val rankLabel: String get() = rank?.let { if (it == 1) "1er" else "${it}e" } ?: "—"

    /** "1 h 12 · 465 km". */
    val timeLabel: String
        get() = listOfNotNull(durationSeconds?.let(::groupDuration), distanceMeters?.let(::groupDistance)).joinToString(" · ")
}

/** The link that lets someone watch the group without driving in it. */
data class GroupLink(val url: String, val token: String, val observers: Int)

/** The group trip as one of its drivers sees it. */
data class TripGroup(
    /** The server's clock when it answered (epoch millis): positions are placed in time with it. */
    val serverNowMs: Long?,
    val id: String,
    /** What the others type to join, "K7M2PQ". */
    val code: String,
    val isHost: Boolean,
    /** Who leads the group now — the role passes on when the host leaves. */
    val hostName: String?,
    val toLabel: String?,
    val destination: GeoPoint?,
    val maxMembers: Int,
    /** Set once the trip is over: the ranking stops moving. */
    val finishedAtMs: Long?,
    /** The host cancelled it: nothing to rank, it only has to be forgotten. */
    val isCancelled: Boolean,
    val ranking: List<GroupRankEntry>,
    val link: GroupLink?,
    val sharing: Boolean,
    val observable: Boolean,
    val myState: GroupMemberState,
    val myRank: Int?,
    val members: List<GroupMember>,
) {
    val isOver: Boolean get() = finishedAtMs != null

    /** Still being driven: someone may still move on the map. */
    val isLive: Boolean get() = finishedAtMs == null

    /** I am on my way, or about to be: my position may go up. */
    val amDriving: Boolean get() = myState == GroupMemberState.Invited || myState == GroupMemberState.Driving

    /** Equal but for the server's clock: a group that did not change is not news. */
    fun sameAs(other: TripGroup?): Boolean = other != null && copy(serverNowMs = null) == other.copy(serverNowMs = null)
}

/** The group as someone holding the link sees it: only the drivers who agreed to be seen. */
data class ObservedGroup(
    val toLabel: String?,
    val destination: GeoPoint?,
    val finishedAtMs: Long?,
    val isCancelled: Boolean,
    val ranking: List<GroupRankEntry>,
    val members: List<GroupMember>,
    /** Their routes, in the same order as [members]. */
    val routes: List<List<GeoPoint>>,
) {
    val isOver: Boolean get() = finishedAtMs != null
}

/** One member's position, as the stream pushes it the moment their phone sends it. */
data class GroupPosition(
    val memberId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    /** When the server received it, on the server's clock (epoch millis). */
    val atMs: Long,
    val speedKmh: Int?,
    val progress: Double,
    val remainingMeters: Int?,
    val etaAtMs: Long?,
    /** The server's clock when it pushed it. */
    val serverNowMs: Long?,
)

/** What the group stream brings. */
sealed interface GroupEvent {
    /** The whole group, when its shape changed. */
    data class Group(val group: TripGroup) : GroupEvent

    /** One member moved. */
    data class Position(val position: GroupPosition) : GroupEvent

    /** I am in no group any more. */
    data object Gone : GroupEvent
}

/** What the backend says about my group, when asked. */
sealed interface GroupAnswer {
    data class Group(val group: TripGroup) : GroupAnswer

    /** I am in no group any more: left, dropped, or told once that it was cancelled. */
    data object Gone : GroupAnswer

    /** No answer — the network. What was known is kept, and asked again later. */
    data object Failed : GroupAnswer

    /** Heard, nothing more to say (a light send while the stream tells the rest). */
    data object Ok : GroupAnswer
}

/**
 * A member's card, opened from their photo: who they are and how they drive. Only the other
 * members of the same group can read it; [stats] is null when the driver hid them.
 */
data class MemberCard(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val role: Role,
    /** The month they joined, epoch millis of its first day. */
    val memberSinceMs: Long?,
    /** "Note de confiance", 0..5. */
    val trust: Double,
    val stats: Stats?,
    /** Where they stand in this trip. */
    val live: GroupMember,
    val isHost: Boolean,
) {
    data class Stats(
        val distanceMeters: Int,
        val driveDurationSeconds: Int,
        val tripCount: Int,
        val reportsDeclared: Int,
        val reportsConfirmed: Int,
    )

    /** "Membre depuis septembre 2026". */
    val memberSinceLabel: String?
        get() = memberSinceMs?.let {
            "Membre depuis " + java.text.SimpleDateFormat("LLLL yyyy", Locale.FRANCE).format(java.util.Date(it))
        }
}

/**
 * "Trajet en groupe" (`/api/trips/group`): up to five drivers, each from their own start, one
 * destination. Like the plain share, the backend holds it in memory and writes nothing down.
 */
class TripGroupApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** The server writes at least every 15 s on the stream: 45 s of silence is a dead line. */
    private val streamClient = client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    /** Opens a group and hands back its joining code. */
    suspend fun create(toLabel: String?, destination: GeoPoint, route: List<GeoPoint>, token: String?): TripGroup? {
        val json = JSONObject().put("destination", JSONObject().put("lat", destination.lat).put("lon", destination.lon))
        toLabel?.let { json.put("toLabel", it) }
        if (route.isNotEmpty()) json.put("route", TripJson.coordinates(route))
        return group("POST", "/api/trips/group", json, token)
    }

    /** Joins the group behind a code; null when it is unknown, full or over. */
    suspend fun join(code: String, token: String?): TripGroup? =
        group("POST", "/api/trips/group/join", JSONObject().put("code", code), token)

    /** My group as it stands — or none, or no answer. */
    suspend fun mine(token: String?): GroupAnswer = answer("GET", "/api/trips/group", null, token)

    /** Where I am and how far along I am — and whether the others may still see it. */
    suspend fun update(
        position: GeoPoint? = null,
        bearing: Double? = null,
        speedKmh: Int? = null,
        remainingMeters: Int? = null,
        etaSeconds: Int? = null,
        progress: Double? = null,
        distanceMeters: Int? = null,
        route: List<GeoPoint>? = null,
        started: Boolean = false,
        arrived: Boolean = false,
        sharing: Boolean? = null,
        observable: Boolean? = null,
        lite: Boolean = false,
        token: String?,
    ): GroupAnswer {
        val json = JSONObject()
        position?.let { json.put("lat", it.lat).put("lon", it.lon) }
        bearing?.let { json.put("bearing", it) }
        speedKmh?.let { json.put("speedKmh", it) }
        remainingMeters?.let { json.put("remainingM", it) }
        etaSeconds?.let { json.put("etaS", it) }
        progress?.let { json.put("progress", it) }
        distanceMeters?.let { json.put("distanceM", it) }
        if (route != null && route.isNotEmpty()) json.put("route", TripJson.coordinates(route))
        // "En route" is a state, not a position: said even by a driver who does not share.
        if (started) json.put("started", true)
        if (arrived) json.put("arrived", true)
        sharing?.let { json.put("sharing", it) }
        observable?.let { json.put("observable", it) }
        // The stream already says everything: the answer can be a mere "ok".
        if (lite) json.put("lite", true)
        return answer("PATCH", "/api/trips/group/me", json, token)
    }

    /**
     * The group as it changes, pushed by the backend (Server-Sent Events): the whole group when
     * its shape changes, one position the moment a member's phone sends it. The flow ends when
     * the connection drops (or stays silent past its heartbeat); the caller opens it again.
     */
    fun stream(token: String?): Flow<GroupEvent> = channelFlow {
        val request = TripJson.request(baseUrl, "GET", "/api/trips/group/stream", null, token)
            .newBuilder().header("Accept", "text/event-stream").build()
        val call = streamClient.newCall(request)
        launch(Dispatchers.IO) {
            runCatching {
                call.execute().use { response ->
                    if (response.code == 404) send(GroupEvent.Gone)
                    if (response.code != 200) return@use
                    val source = response.body?.source() ?: return@use
                    // Each event is one "event:" line, then one "data:" line (the server writes it
                    // so): the data line is read with the event named just before it.
                    var name = ""
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> name = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                val json = runCatching { JSONObject(line.substring(5)) }.getOrNull()
                                if (json != null) {
                                    when (name) {
                                        "group" -> parseGroup(json)?.let { send(GroupEvent.Group(it)) }
                                        "pos" -> parsePosition(json)?.let { send(GroupEvent.Position(it)) }
                                    }
                                }
                                name = ""
                            }
                        }
                    }
                }
            }
            close()
        }
        awaitClose { call.cancel() }
    }

    /**
     * The other members' routes the phone does not hold yet: [known] maps a member to the version
     * it has. Null when there is no answer; an empty list when nothing changed.
     */
    suspend fun routes(known: Map<String, Int>, token: String?): List<GroupRoute>? = withContext(Dispatchers.IO) {
        val list = known.entries.map { "${it.key}:${it.value}" }.sorted().joinToString(",")
        val path = "/api/trips/group/routes" + if (list.isEmpty()) "" else "?known=" + URLEncoder.encode(list, "UTF-8")
        runCatching {
            client.newCall(TripJson.request(baseUrl, "GET", path, null, token)).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val routes = JSONObject(r.body?.string() ?: "").optJSONArray("routes") ?: return@use null
                (0 until routes.length()).mapNotNull { i ->
                    val route = routes.optJSONObject(i) ?: return@mapNotNull null
                    GroupRoute(route.optString("id"), route.optInt("rev", 0), TripJson.lonLats(route.optJSONArray("route")))
                }
            }
        }.getOrNull()
    }

    /** A member's card; null when they are not in my group any more, or the network fails. */
    suspend fun card(memberId: String, token: String?): MemberCard? = withContext(Dispatchers.IO) {
        val path = "/api/trips/group/member/" + URLEncoder.encode(memberId, "UTF-8") + "/card"
        runCatching {
            client.newCall(TripJson.request(baseUrl, "GET", path, null, token)).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: "").optJSONObject("card")?.let(::parseCard)
            }
        }.getOrNull()
    }

    /** I step out. */
    suspend fun leave(token: String?): Boolean = plain("POST", "/api/trips/group/leave", token)

    /** The host cancels the trip. */
    suspend fun cancel(token: String?): TripGroup? = group("DELETE", "/api/trips/group", null, token)

    /** The host opens the link that lets others watch, or revokes it. */
    suspend fun openLink(token: String?): TripGroup? = group("POST", "/api/trips/group/link", null, token)

    suspend fun revokeLink(token: String?): TripGroup? = group("DELETE", "/api/trips/group/link", null, token)

    /** The group behind a watch link; null once it is revoked or the trip is over. */
    suspend fun watch(linkToken: String, token: String?): ObservedGroup? = withContext(Dispatchers.IO) {
        val path = "/api/trips/group/watch/" + URLEncoder.encode(linkToken, "UTF-8")
        runCatching {
            client.newCall(TripJson.request(baseUrl, "GET", path, null, token)).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val group = JSONObject(r.body?.string() ?: "").optJSONObject("group") ?: return@use null
                val members = group.optJSONArray("members")
                val memberJson = (0 until (members?.length() ?: 0)).mapNotNull { members?.optJSONObject(it) }
                ObservedGroup(
                    toLabel = TripJson.string(group, "toLabel"),
                    destination = TripJson.point(group.optJSONObject("destination")),
                    finishedAtMs = TripJson.millis(group, "finishedAt"),
                    isCancelled = group.optBoolean("cancelled"),
                    ranking = ranking(group),
                    members = memberJson.map(::parseMember),
                    routes = memberJson.map { TripJson.lonLats(it.optJSONArray("route")) },
                )
            }
        }.getOrNull()
    }

    private suspend fun plain(method: String, path: String, token: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.newCall(TripJson.request(baseUrl, method, path, null, token)).execute().use { it.isSuccessful } }
            .getOrDefault(false)
    }

    /** A group, none (the backend says so: null, or 404), or no answer at all. */
    private suspend fun answer(method: String, path: String, json: JSONObject?, token: String?): GroupAnswer =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(TripJson.request(baseUrl, method, path, json, token)).execute().use { r ->
                    if (r.code == 404) return@use GroupAnswer.Gone
                    if (!r.isSuccessful) return@use GroupAnswer.Failed
                    val body = JSONObject(r.body?.string() ?: "{}")
                    if (body.optBoolean("ok") && !body.has("group")) return@use GroupAnswer.Ok
                    val group = body.optJSONObject("group")?.let(::parseGroup) ?: return@use GroupAnswer.Gone
                    GroupAnswer.Group(group)
                }
            }.getOrDefault(GroupAnswer.Failed)
        }

    private suspend fun group(method: String, path: String, json: JSONObject?, token: String?): TripGroup? =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(TripJson.request(baseUrl, method, path, json, token)).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    JSONObject(r.body?.string() ?: "").optJSONObject("group")?.let(::parseGroup)
                }
            }.getOrNull()
        }

    private fun parseGroup(json: JSONObject): TripGroup? {
        val id = json.optString("id").ifBlank { return null }
        val me = json.optJSONObject("me")
        val link = json.optJSONObject("link")?.let { l ->
            val url = l.optString("url")
            val token = l.optString("token")
            if (url.isBlank() || token.isBlank()) null else GroupLink(url, token, l.optInt("observers"))
        }
        val members = json.optJSONArray("members")
        return TripGroup(
            serverNowMs = json.optLong("now").takeIf { it > 0 },
            id = id,
            code = json.optString("code"),
            isHost = json.optBoolean("host"),
            hostName = TripJson.string(json, "hostName"),
            toLabel = TripJson.string(json, "toLabel"),
            destination = TripJson.point(json.optJSONObject("destination")),
            maxMembers = json.optInt("maxMembers", 5).coerceAtLeast(1),
            finishedAtMs = TripJson.millis(json, "finishedAt"),
            isCancelled = json.optBoolean("cancelled"),
            ranking = ranking(json),
            link = link,
            sharing = me?.optBoolean("sharing", true) ?: true,
            observable = me?.optBoolean("observable", true) ?: true,
            myState = GroupMemberState.fromWire(me?.optString("state")),
            myRank = me?.let { TripJson.int(it, "rank") },
            members = (0 until (members?.length() ?: 0)).mapNotNull { members?.optJSONObject(it)?.let(::parseMember) },
        )
    }

    private fun parseMember(json: JSONObject): GroupMember {
        val position = json.optJSONObject("position")
        return GroupMember(
            id = json.optString("id"),
            name = TripJson.string(json, "name") ?: "Un conducteur",
            state = GroupMemberState.fromWire(json.optString("state")),
            sharing = json.optBoolean("sharing", true),
            online = json.optBoolean("online"),
            rank = TripJson.int(json, "rank"),
            position = TripJson.point(position),
            bearing = position?.optDouble("bearing")?.takeIf { it.isFinite() },
            speedKmh = TripJson.int(json, "speedKmh"),
            progress = json.optDouble("progress", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            remainingMeters = TripJson.int(json, "remainingM"),
            etaAtMs = TripJson.millis(json, "etaAt"),
            durationSeconds = TripJson.int(json, "durationS"),
            distanceMeters = TripJson.int(json, "distanceM"),
            avatarUrl = TripJson.string(json, "avatarUrl"),
            positionAtMs = position?.optDouble("at")?.takeIf { it.isFinite() && it > 0 }?.toLong(),
            routeRev = json.optInt("routeRev", 0),
        )
    }

    private fun parsePosition(json: JSONObject): GroupPosition? {
        val lat = json.optDouble("lat")
        val lon = json.optDouble("lon")
        val at = json.optDouble("at")
        val id = json.optString("id")
        if (!lat.isFinite() || !lon.isFinite() || !at.isFinite() || id.isBlank()) return null
        return GroupPosition(
            memberId = id,
            lat = lat,
            lon = lon,
            bearing = json.optDouble("bearing").takeIf { it.isFinite() },
            atMs = at.toLong(),
            speedKmh = TripJson.int(json, "speedKmh"),
            progress = json.optDouble("progress", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            remainingMeters = TripJson.int(json, "remainingM"),
            etaAtMs = TripJson.millis(json, "etaAt"),
            serverNowMs = json.optLong("now").takeIf { it > 0 },
        )
    }

    private fun parseCard(json: JSONObject): MemberCard? {
        val person = json.optJSONObject("live")?.let(::parseMember) ?: return null
        val since = TripJson.string(json, "memberSince")?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size >= 2 }?.let {
            Calendar.getInstance().apply { clear(); set(it[0], it[1] - 1, 1) }.timeInMillis
        }
        val stats = json.optJSONObject("stats")?.let { s ->
            MemberCard.Stats(
                distanceMeters = s.optInt("distanceMeters"),
                driveDurationSeconds = s.optInt("driveDurationSeconds"),
                tripCount = s.optInt("tripCount"),
                reportsDeclared = s.optInt("reportsDeclared"),
                reportsConfirmed = s.optInt("reportsConfirmed"),
            )
        }
        return MemberCard(
            id = json.optString("id"),
            name = TripJson.string(json, "username") ?: person.name,
            avatarUrl = TripJson.string(json, "avatarUrl") ?: person.avatarUrl,
            role = Role.fromWire(json.optString("role")),
            memberSinceMs = since,
            trust = json.optDouble("trust", 2.5).coerceIn(0.0, 5.0),
            stats = stats,
            live = person,
            isHost = json.optJSONObject("live")?.optBoolean("host") ?: false,
        )
    }

    private fun ranking(json: JSONObject): List<GroupRankEntry> {
        val list = json.optJSONArray("ranking") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            GroupRankEntry(
                id = r.optString("id"),
                name = TripJson.string(r, "name") ?: "Un conducteur",
                rank = TripJson.int(r, "rank"),
                state = GroupMemberState.fromWire(r.optString("state")),
                durationSeconds = TripJson.int(r, "durationS"),
                distanceMeters = TripJson.int(r, "distanceM"),
            )
        }
    }
}
