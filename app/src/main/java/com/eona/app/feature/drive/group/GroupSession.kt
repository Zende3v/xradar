package com.eona.app.feature.drive.group

import com.eona.app.core.geo.Geo
import com.eona.app.core.geo.LineSimplifier
import com.eona.app.core.geo.RoutePath
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.Place
import com.eona.app.core.model.PlaceKind
import com.eona.app.core.model.Route
import com.eona.app.core.model.TripGroupRank
import com.eona.app.core.model.TripGroupResult
import com.eona.app.data.trips.GroupAnswer
import com.eona.app.data.trips.GroupEvent
import com.eona.app.data.trips.GroupMember
import com.eona.app.data.trips.GroupMemberState
import com.eona.app.data.trips.GroupPosition
import com.eona.app.data.trips.MemberCard
import com.eona.app.data.trips.TripGroup
import com.eona.app.data.trips.TripGroupApi
import com.eona.app.data.trips.TripShare
import com.eona.app.data.trips.TripShareApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One other member in the strip over the map: picture, name, what they are doing. */
data class GroupChip(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val colorIndex: Int,
    /** "112 km/h · 34 km", "Pas encore parti", "Ne partage pas sa position"… */
    val detail: String,
    /** On the map, so the camera can follow them. */
    val onMap: Boolean,
)

/** Which member's card is open, and in which colour they are drawn. */
data class MemberCardTarget(val id: String, val colorIndex: Int)

/** What the session reads of the trip being driven, from the driving screen's model. */
interface TripContext {
    val token: String?
    val myAccountId: String?
    /** True once the driver has really been on the route of this trip. */
    val tripUnderway: Boolean
    val location: LocationSample?
    val destination: Place?
    val route: Route?
    /** Bumped at each new route: a route travels to the group once per version. */
    val routeVersion: Int
    /** The route followed and where the driver is along it; null off it. */
    fun progress(): Pair<RoutePath, RoutePath.Match>?
    /** Metres driven in this trip so far. */
    val drivenMeters: Int?
    fun setDestination(place: Place)
    /** The frozen ranking joins the trip saved in the history. */
    fun attachGroupResult(result: TripGroupResult, tripId: String)
}

/**
 * "Partager mon trajet" and "Trajet en groupe", as the driving screen drives them — the same rules
 * as the iOS app.
 *
 * The group follows the trip: it is opened on a destination, it rides along, and it ends with it.
 * Stopping the navigation before the destination is leaving the group. A group left or cancelled
 * here is never taken back from the backend, whatever it answers. While the group runs, the
 * others' positions come through the stream the moment they are sent; my own goes up every few
 * seconds, and only once the trip has really started.
 */
class GroupSession(private val scope: CoroutineScope, private val trip: TripContext) {

    private val shareApi = TripShareApi()
    private val groupApi = TripGroupApi()

    // ---- "Partager mon trajet" ----
    private val _share = MutableStateFlow<TripShare?>(null)
    /** The live link, null when nothing is shared. */
    val share: StateFlow<TripShare?> = _share.asStateFlow()
    private val _openingShare = MutableStateFlow(false)
    val openingShare: StateFlow<Boolean> = _openingShare.asStateFlow()
    private var lastShareUpdateAt = 0L

    // ---- "Trajet en groupe" ----
    private val _group = MutableStateFlow<TripGroup?>(null)
    /** The group being driven, null outside one. */
    val group: StateFlow<TripGroup?> = _group.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _sharing = MutableStateFlow(true)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()
    private val _observable = MutableStateFlow(true)
    val observable: StateFlow<Boolean> = _observable.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** A word about the group, a few seconds: someone joined, left, arrived… */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _chips = MutableStateFlow<List<GroupChip>>(emptyList())
    /** The strip over the map: one chip per other member, refreshed every few seconds. */
    val chips: StateFlow<List<GroupChip>> = _chips.asStateFlow()
    private val _focus = MutableStateFlow<String?>(null)
    /** The member the camera follows (null = me). */
    val focus: StateFlow<String?> = _focus.asStateFlow()
    private val _finished = MutableStateFlow<TripGroup?>(null)
    /** The group once over: the ranking card shows it until it is read. */
    val finished: StateFlow<TripGroup?> = _finished.asStateFlow()

    /** The others on the main map: written here, read by the map at every frame. */
    val mapLayer = GroupMapLayer()

    private var ticker: Job? = null
    private var stream: Job? = null
    /** True while the stream is open: my own sends can then be light. */
    private var streamLive = false
    /** The route version already sent to the group: a route travels once, not every tick. */
    private var routeSent = -1
    /** The trip saved for this group ride, waiting for the ranking to be frozen. */
    private var recordId: String? = null
    /** Groups left or cancelled here: whatever the backend still says, they do not come back. */
    private val leftIds = HashSet<String>()
    /** Switches of the group waiting for the backend: older ticks must not undo them. */
    private var flagsPending = 0
    /** Routes of the others already on the phone, by member. */
    private var knownRoutes = HashMap<String, Int>()
    private var fetchingRoutes = false
    /** Each member's last position, for the strip's speed and distance. */
    private val latestPositions = HashMap<String, GroupPosition>()
    private var ticks = 0

    val inGroup: Boolean get() = _group.value != null
    val isLive: Boolean get() = _group.value?.isLive == true

    // ---- Link ----

    /**
     * Opens a link on the trip being driven. Only once the trip has really started: before the
     * driver is on the road there is nothing to follow — and so nothing to stop either.
     */
    suspend fun startSharing(): TripShare? {
        val route = trip.route ?: return null
        if (!trip.tripUnderway) return null
        val token = trip.token ?: return null
        _openingShare.value = true
        val destination = trip.destination
        val opened = shareApi.open(destination?.name, destination?.let { GeoPoint(it.lat, it.lon) }, route.points, token)
        _openingShare.value = false
        _share.value = opened
        if (opened != null) pushShare(force = true)
        return opened
    }

    /** Stops sharing: the link dies at once. */
    suspend fun stopSharing() {
        if (_share.value == null) return
        shareApi.close(trip.token)
        _share.value = null
    }

    /** Where the driver is and what is left of the trip, sent while someone may be watching. */
    suspend fun pushShare(force: Boolean = false, arrived: Boolean = false) {
        if (_share.value == null) return
        val token = trip.token ?: return
        val now = System.currentTimeMillis()
        if (!force && !arrived && now - lastShareUpdateAt < SHARE_UPDATE_MS) return
        lastShareUpdateAt = now
        val fix = trip.location
        val left = remaining()
        val updated = shareApi.update(
            position = fix?.let { GeoPoint(it.latitude, it.longitude) },
            bearing = fix?.bearingDeg?.toDouble(),
            remainingMeters = left?.first,
            etaSeconds = left?.second,
            arrived = arrived,
            token = token,
        )
        // Refused (link over, trip finished elsewhere): the button goes back to "partager".
        _share.value = if (arrived) null else updated ?: _share.value
    }

    /** Metres left and seconds left along the route followed, or null off it. */
    private fun remaining(): Pair<Int, Int>? {
        val route = trip.route ?: return null
        val (path, match) = trip.progress() ?: return null
        val left = (path.totalMeters - match.alongMeters).coerceAtLeast(0.0)
        val part = if (path.totalMeters > 0) left / path.totalMeters else 0.0
        return left.roundToInt() to (route.durationSeconds * part).roundToInt()
    }

    // ---- Group: the driver's actions ----

    /** Opens a group on the destination chosen, and hands back its joining code. */
    suspend fun create(): TripGroup? {
        val token = trip.token ?: return null
        val destination = trip.destination ?: return null
        _busy.value = true
        val opened = groupApi.create(destination.name, GeoPoint(destination.lat, destination.lon), trip.route?.points.orEmpty(), token)
        _busy.value = false
        adopt(opened, routeSentNow = trip.route != null)
        return opened
    }

    /**
     * Joins the group behind a code. Its destination becomes mine — unless I already drive there —
     * and my route is sent as soon as it is known.
     */
    suspend fun join(code: String): TripGroup? {
        val clean = code.trim().uppercase()
        val token = trip.token ?: return null
        if (clean.isEmpty()) return null
        _busy.value = true
        val joined = groupApi.join(clean, token)
        _busy.value = false
        joined ?: return null
        adopt(joined, routeSentNow = false)
        joined.destination?.let { point ->
            val there = trip.destination?.let {
                Geo.haversine(it.lat, it.lon, point.lat, point.lon) < SAME_PLACE_M
            } ?: false
            if (!there) {
                trip.setDestination(
                    Place(
                        id = "group-${joined.code}",
                        name = joined.toLabel ?: "Destination du groupe",
                        subtitle = "Trajet en groupe",
                        kind = PlaceKind.Result,
                        lat = point.lat,
                        lon = point.lon,
                    ),
                )
            }
        }
        return joined
    }

    /**
     * I step out of the group; a host hands the role to the next driver. Forgotten here at once,
     * and for good: even if the backend has not heard it yet, it will not come back.
     */
    suspend fun leave() {
        val current = _group.value ?: return
        val token = trip.token
        leftIds += current.id
        forget()
        groupApi.leave(token)
    }

    /** The host cancels the trip for everyone; the others are told at their next call. */
    suspend fun cancel() {
        val current = _group.value ?: return
        if (!current.isHost) return
        val token = trip.token
        leftIds += current.id
        forget()
        groupApi.cancel(token)
    }

    /** The ranking has been read: the group leaves the screen, the phone and the backend. */
    fun dismiss() {
        val current = _group.value ?: _finished.value ?: return
        val token = trip.token
        leftIds += current.id
        forget()
        scope.launch { groupApi.leave(token) }
    }

    /** The link that lets someone watch the group, opened or revoked by the host. */
    suspend fun setLink(open: Boolean) {
        if (_group.value?.isHost != true) return
        _busy.value = true
        val updated = if (open) groupApi.openLink(trip.token) else groupApi.revokeLink(trip.token)
        _busy.value = false
        if (updated != null && _group.value != null) take(updated)
    }

    /** "Ma position et ma vitesse" — off, nothing of mine leaves the phone. */
    suspend fun setSharing(on: Boolean) {
        if (_group.value == null) return
        _sharing.value = on
        flagsPending += 1
        val answer = groupApi.update(sharing = on, token = trip.token)
        flagsPending -= 1
        apply(answer)
    }

    /** "Visible depuis le lien" — off, I stay out of the public link, in the group all the same. */
    suspend fun setObservable(on: Boolean) {
        if (_group.value == null) return
        _observable.value = on
        flagsPending += 1
        val answer = groupApi.update(observable = on, token = trip.token)
        flagsPending -= 1
        apply(answer)
    }

    /** The group as the backend has it right now, when a screen opens. */
    suspend fun refresh() {
        if (trip.token == null) return
        apply(groupApi.mine(trip.token), adopting = true)
    }

    fun acknowledgeNotice() {
        _notice.value = null
    }

    /** The camera follows one member (null: back to me). */
    fun focusOn(id: String?) {
        _focus.value = id
        mapLayer.setFocus(id)
    }

    /** Everyone at once on the map. */
    fun showEveryone() {
        focusOn(null)
        mapLayer.requestOverview()
    }

    suspend fun memberCard(id: String): MemberCard? = groupApi.card(id, trip.token)

    /** The card to open for a member: with the colour of their place in the group. */
    fun cardTarget(id: String): MemberCardTarget {
        val members = (_group.value ?: _finished.value)?.members.orEmpty()
        return MemberCardTarget(id, members.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }

    // ---- The trip's end ----

    /** The trip ended: arrived at the destination, or stopped on the way. */
    fun onTripEnded(arrivedAtDestination: Boolean, drivenMeters: Int, savedTripId: String?) {
        // Whoever follows the trip sees the arrival, then the link goes out. Stopped on the way,
        // the link simply stops: nobody is told "arrivé" for a trip left unfinished.
        if (_share.value != null) {
            scope.launch { if (arrivedAtDestination) pushShare(arrived = true) else stopSharing() }
        }
        if (_group.value?.isLive == true) {
            if (arrivedAtDestination) {
                recordId = savedTripId
                scope.launch { push(arrived = true, distance = drivenMeters) }
            } else {
                scope.launch { leave() }
                notify("Navigation arrêtée : tu as quitté le trajet en groupe.")
            }
        }
    }

    /** Called at each GPS fix: the link hears from the driver every few seconds. */
    fun onFix() {
        if (_share.value != null) scope.launch { pushShare() }
    }

    // ---- What the backend says ----

    /**
     * What the backend said about my group, taken in. A group I left is never taken back, a
     * cancelled one is announced then forgotten, and no answer keeps what was known.
     */
    private fun apply(answer: GroupAnswer, adopting: Boolean = false) {
        when (answer) {
            GroupAnswer.Failed, GroupAnswer.Ok -> Unit
            GroupAnswer.Gone -> {
                if (_group.value?.isLive == true) notify("Tu ne fais plus partie du trajet en groupe.")
                if (_group.value != null) forget()
            }
            is GroupAnswer.Group -> {
                val fresh = answer.group
                if (fresh.id in leftIds) {
                    // Left here, not yet heard there: said again, and ignored meanwhile.
                    val token = trip.token
                    scope.launch { groupApi.leave(token) }
                    return
                }
                if (fresh.isCancelled) {
                    if (_group.value != null || adopting) {
                        notify(if (fresh.isHost) "Trajet en groupe annulé." else "${fresh.hostName ?: "Le créateur"} a annulé le trajet en groupe.")
                    }
                    forget()
                    return
                }
                if (_group.value == null) {
                    if (adopting) adopt(fresh, routeSentNow = false)
                    return
                }
                take(fresh)
            }
        }
    }

    /** A newer state of the group I am in. */
    private fun take(fresh: TripGroup) {
        announceChanges(_group.value, fresh)
        if (!fresh.sameAs(_group.value)) _group.value = fresh
        syncFlags(fresh)
        publishToMap(fresh)
        if (fresh.isOver) finish(fresh)
    }

    /** Somebody came, left, arrived, or the lead changed: said once, on the HUD. */
    private fun announceChanges(old: TripGroup?, fresh: TripGroup) {
        if (old == null || old.id != fresh.id) return
        val me = trip.myAccountId
        val before = old.members.associateBy { it.id }
        for (member in fresh.members) {
            if (member.id == me) continue
            val was = before[member.id]
            when {
                member.state == GroupMemberState.Left && was != null && was.state != GroupMemberState.Left ->
                    notify("${member.name} a quitté le trajet en groupe.")
                member.state != GroupMemberState.Left && (was == null || was.state == GroupMemberState.Left) ->
                    notify("${member.name} a rejoint le groupe.")
                member.state == GroupMemberState.Arrived && was?.state != GroupMemberState.Arrived -> {
                    val rank = member.rank?.let { if (it == 1) " en premier" else " ${it}e" } ?: ""
                    notify("${member.name} est arrivé$rank.")
                }
            }
        }
        // Dropped after a long silence: gone from the list without a word from their phone.
        for (was in old.members) {
            if (was.id != me && was.isPresent && fresh.members.none { it.id == was.id }) {
                notify("${was.name} n'est plus dans le trajet en groupe.")
            }
        }
        if (fresh.isHost && !old.isHost && fresh.isLive) notify("Tu mènes maintenant le groupe.")
    }

    /**
     * The others, for the map and the strip: only those still in the group and sharing, each in the
     * colour of their place in the group. Their routes are asked for when they changed.
     */
    private fun publishToMap(fresh: TripGroup) {
        mapLayer.noteServerTime(fresh.serverNowMs)
        val me = trip.myAccountId
        val indexed = fresh.members.withIndex().filter { it.value.id != me && it.value.isPresent }
        // On the map: those who share, while the trip runs.
        val shown = if (fresh.isLive) indexed.filter { it.value.sharing } else emptyList()
        mapLayer.setMembers(shown.map { (index, member) -> GroupMapMember(member.id, member.name, member.avatarUrl, index) })
        // The positions the group itself carries join those already heard; the older ones are ignored.
        for ((_, member) in shown) {
            val position = member.position ?: continue
            val at = member.positionAtMs ?: continue
            mapLayer.addSample(member.id, GroupSample(at / 1000.0, position.lat, position.lon, member.bearing, (member.speedKmh ?: 0) / 3.6))
        }
        val shownIds = shown.map { it.value.id }.toSet()
        latestPositions.keys.retainAll(shownIds)
        refreshChips(fresh)
        _focus.value?.let { if (it !in shownIds) focusOn(null) }

        // Routes: kept for those who share, dropped for the others, asked for when they changed.
        mapLayer.keepRoutes(shownIds)
        knownRoutes.keys.retainAll(shownIds)
        // Any version not held yet — the host's first route is version 0, given with the group.
        val revs = shown.associate { it.value.id to it.value.routeRev }
        val stale = revs.any { (id, rev) -> knownRoutes[id] != rev }
        if (fresh.isLive && stale && !fetchingRoutes) {
            val colors = shown.associate { it.value.id to it.index }
            scope.launch { fetchRoutes(colors, revs) }
        }
    }

    /** The routes that changed, and only those. */
    private suspend fun fetchRoutes(colors: Map<String, Int>, revs: Map<String, Int>) {
        fetchingRoutes = true
        try {
            val routes = groupApi.routes(HashMap(knownRoutes), trip.token) ?: return
            if (_group.value?.isLive != true) return
            // Asked about, answered: a member with no route yet is not asked again until theirs changes.
            knownRoutes.putAll(revs)
            for (route in routes) {
                knownRoutes[route.memberId] = route.rev
                // Lightened for the map: a friend's 500 km route of 1500 points is heavy to redraw.
                val light = LineSimplifier.simplify(route.points, GROUP_ROUTE_MAX_POINTS)
                mapLayer.setRoute(route.memberId, GroupMapRoute(route.rev, colors[route.memberId] ?: 0, light))
            }
        } finally {
            fetchingRoutes = false
        }
    }

    /** The strip over the map: who, and what they are doing — refreshed every few seconds. */
    private fun refreshChips(current: TripGroup? = _group.value) {
        current ?: return
        val me = trip.myAccountId
        val chips = current.members.withIndex()
            .filter { it.value.id != me && it.value.isPresent }
            .map { (index, member) ->
                GroupChip(
                    id = member.id,
                    name = member.name,
                    avatarUrl = member.avatarUrl,
                    colorIndex = index,
                    detail = chipDetail(member, latestPositions[member.id]),
                    onMap = current.isLive && member.sharing && mapLayer.samples[member.id] != null,
                )
            }
        if (chips != _chips.value) _chips.value = chips
    }

    /** "112 km/h · 34 km", "Pas encore parti", "Signal perdu"… from the freshest news. */
    private fun chipDetail(member: GroupMember, position: GroupPosition?): String {
        if (!member.sharing) return "Ne partage pas sa position"
        when (member.state) {
            GroupMemberState.Invited -> return "Pas encore parti"
            GroupMemberState.Left -> return "A quitté le trajet"
            GroupMemberState.Arrived -> return member.detailLabel
            GroupMemberState.Driving -> Unit
        }
        val at = position?.atMs ?: member.positionAtMs
        if (at != null && mapLayer.serverNow - at / 1000.0 > SILENT_S) return "Signal perdu"
        val parts = listOfNotNull(
            (position?.speedKmh ?: member.speedKmh)?.let { "$it km/h" },
            (position?.remainingMeters ?: member.remainingMeters)?.let {
                if (it < 1000) "$it m" else "${Math.round(it / 1000.0)} km"
            },
        )
        return if (parts.isEmpty()) "En route" else parts.joinToString(" · ")
    }

    // ---- Stream and ticker ----

    /**
     * The group stream: open while the group runs, opened again after a drop (sooner, then less
     * often). While it is open, the others' positions arrive the moment they are sent.
     */
    private fun startStream() {
        if (stream != null) return
        stream = scope.launch {
            var pause = 1_000L
            while (isActive && _group.value?.isLive == true) {
                runCatching {
                    groupApi.stream(trip.token).collect { event ->
                        streamLive = true
                        pause = 1_000L
                        receive(event)
                    }
                }
                streamLive = false
                if (_group.value?.isLive != true) break
                delay(pause)
                pause = (pause * 2).coerceAtMost(20_000L)
            }
        }
    }

    private fun stopStream() {
        stream?.cancel()
        stream = null
        streamLive = false
    }

    /** One piece of news from the stream. */
    private fun receive(event: GroupEvent) {
        when (event) {
            is GroupEvent.Group -> apply(GroupAnswer.Group(event.group))
            is GroupEvent.Position -> {
                val p = event.position
                mapLayer.noteServerTime(p.serverNowMs)
                if (mapLayer.members.none { it.id == p.memberId }) return
                mapLayer.addSample(p.memberId, GroupSample(p.atMs / 1000.0, p.lat, p.lon, p.bearing, (p.speedKmh ?: 0) / 3.6))
                latestPositions[p.memberId] = p
            }
            GroupEvent.Gone -> apply(GroupAnswer.Gone)
        }
    }

    /** While a group runs: my position goes up every few seconds, the strip every other beat. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _group.value?.isLive == true) {
                push()
                ticks += 1
                if (ticks % 2 == 0) refreshChips()
                delay(UPDATE_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * My state for the others. My position goes up only when all three hold: I share, I am on my
     * way, and the trip has really started — not while I am still at home, and not once I am
     * there. Otherwise the call only says my phone is here and asks how the others do.
     */
    private suspend fun push(arrived: Boolean = false, distance: Int? = null) {
        val current = _group.value ?: return
        if (!current.isLive) return
        val token = trip.token ?: return
        val sending = current.sharing && current.amDriving && trip.tripUnderway
        var position: GeoPoint? = null
        var bearing: Double? = null
        var speed: Int? = null
        var left: Pair<Int, Int>? = null
        var advance: Double? = null
        var driven: Int? = null
        var route: List<GeoPoint>? = null
        val version = trip.routeVersion
        if (sending) {
            trip.location?.let { fix ->
                position = GeoPoint(fix.latitude, fix.longitude)
                bearing = fix.bearingDeg?.toDouble()
                speed = fix.speedMps?.let { (it * 3.6f).coerceAtLeast(0f).roundToInt() }
            }
            left = remaining()
            trip.progress()?.let { (path, match) ->
                if (path.totalMeters > 0) advance = (match.alongMeters / path.totalMeters).coerceIn(0.0, 1.0)
            }
            driven = trip.drivenMeters
            // My route travels once per route, not at every tick.
            val points = trip.route?.points
            if (routeSent != version && points != null && points.size >= 2) route = points
        }
        if (arrived && current.sharing) driven = distance ?: driven
        val answer = groupApi.update(
            position = position,
            bearing = bearing,
            speedKmh = speed,
            remainingMeters = left?.first,
            etaSeconds = left?.second,
            progress = advance,
            distanceMeters = driven,
            route = route,
            started = trip.tripUnderway || arrived,
            arrived = arrived,
            // The stream tells me the rest: the answer only has to say "ok".
            lite = streamLive,
            token = token,
        )
        if ((answer is GroupAnswer.Group || answer == GroupAnswer.Ok) && route != null) routeSent = version
        apply(answer)
    }

    // ---- Keeping and forgetting ----

    /** A group arrived at: kept, and the ticker starts. One already over is kept for its ranking. */
    private fun adopt(joined: TripGroup?, routeSentNow: Boolean) {
        joined ?: return
        leftIds -= joined.id
        _group.value = joined
        syncFlags(joined)
        routeSent = if (routeSentNow) trip.routeVersion else -1
        if (joined.isOver) {
            stopTicker()
            stopStream()
            _finished.value = joined
        } else {
            startTicker()
            startStream()
            publishToMap(joined)
        }
    }

    /** Nothing of the group is kept on the phone once it is left, cancelled or read. */
    private fun forget() {
        stopTicker()
        stopStream()
        latestPositions.clear()
        _group.value = null
        _finished.value = null
        mapLayer.clear()
        if (_chips.value.isNotEmpty()) _chips.value = emptyList()
        _focus.value = null
        knownRoutes.clear()
        routeSent = -1
        recordId = null
        syncFlags(null)
    }

    /** The few switches the panel reads; a switch just moved is set by the backend's answer to it. */
    private fun syncFlags(current: TripGroup?) {
        if (flagsPending != 0) return
        _sharing.value = current?.sharing ?: true
        _observable.value = current?.observable ?: true
    }

    private fun notify(message: String) {
        _notice.value = message
        scope.launch {
            delay(NOTICE_MS)
            if (_notice.value == message) _notice.value = null
        }
    }

    /**
     * The group is over: the ranking is frozen, so it joins the trip in the history — the names,
     * the ranks and the times, never anybody else's route.
     */
    private fun finish(finished: TripGroup) {
        val myId = trip.myAccountId
        val id = recordId
        if (id != null && finished.ranking.isNotEmpty()) {
            trip.attachGroupResult(
                TripGroupResult(
                    code = finished.code,
                    myRank = finished.myRank,
                    ranking = finished.ranking.map {
                        TripGroupRank(it.name, it.rank, it.durationSeconds, it.distanceMeters, me = it.id == myId)
                    },
                ),
                id,
            )
        }
        stopTicker()
        stopStream()
        recordId = null
        mapLayer.clear()
        if (_finished.value?.sameAs(finished) != true) _finished.value = finished
    }

    private companion object {
        const val SHARE_UPDATE_MS = 10_000L
        const val UPDATE_MS = 3_000L
        const val SILENT_S = 45.0
        const val GROUP_ROUTE_MAX_POINTS = 600
        const val SAME_PLACE_M = 150.0
        const val NOTICE_MS = 6_000L
    }
}
