package com.eona.app.feature.drive.group

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.eona.app.core.geo.Geo
import com.eona.app.core.geo.RoutePath
import com.eona.app.core.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The other members of a group trip on the MapLibre map: their routes under the driver's own,
 * their photos in a ring of their colour, and a small arrow for their heading.
 *
 * Each frame places every member where they were a few seconds ago — between two positions really
 * received, along their own route. Nothing is guessed ahead, so nothing ever has to come back: the
 * movement is as steady as the driver's own arrow. The member's clock runs with time, a little
 * faster or slower to stay on its goal, never goes back, and never passes the last position heard.
 */
class GroupMapRenderer(private val density: Float, private val scope: CoroutineScope) {

    /** One member as the map carries them: their route, where each position sits along it, and
     *  where they are drawn now. */
    private class Track(var member: GroupMapMember) {
        var routeRev: Int? = null
        var path: RoutePath? = null
        /** Distance along the route of each position (by its time); NaN when off the route. */
        val alongs = HashMap<Double, Double>()
        var lat = 0.0
        var lon = 0.0
        var bearing = 0.0
        var placed = false
        var moving = false
        var online = true
        /** The moment of their trip being shown (server clock, seconds). */
        var playTime: Double? = null
        /** How far behind "now" it aims to be; eased, never jumped. */
        var delay = DEFAULT_DELAY_S
        var lastFrameMs = 0L
        /** The image registered for them, and what it shows. */
        var imageKey: String? = null
    }

    private val tracks = HashMap<String, Track>()
    private var seenVersion = -1
    private var seenOverview = 0
    private var drawnRoutes: Map<String, GroupMapRoute> = emptyMap()
    /** Photos by address, once loaded; the ones being fetched. */
    private val photos = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** The sources and layers: their routes under the driver's ([belowLayer]), them under the driver ([belowMembers]). */
    fun install(style: Style, belowLayer: String, belowMembers: String) {
        drawnRoutes = emptyMap()
        tracks.values.forEach { it.imageKey = null }
        seenVersion = -1
        style.addSource(GeoJsonSource(ROUTES_SOURCE))
        style.addLayerBelow(
            LineLayer(ROUTES_LAYER, ROUTES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineOpacity(0.8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
            belowLayer,
        )
        GroupPalette.argb.forEachIndexed { index, color -> style.addImage("$ARROW_IMAGE$index", arrowBitmap(color)) }
        style.addSource(GeoJsonSource(MEMBERS_SOURCE))
        style.addLayerBelow(
            SymbolLayer(ARROWS_LAYER, MEMBERS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("arrow")),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                // The offset turns with the icon: the arrow circles the photo, pointing ahead.
                PropertyFactory.iconOffset(arrayOf(0f, -(PHOTO_DP / 2f + RING_DP + 6f))),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(Expression.get("alpha")),
            ).also { it.setFilter(Expression.eq(Expression.get("moving"), true)) },
            belowMembers,
        )
        style.addLayerBelow(
            SymbolLayer(MEMBERS_LAYER, MEMBERS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(Expression.get("alpha")),
            ),
            belowMembers,
        )
    }

    /** Each frame: take in what changed, then place every member. */
    fun step(style: Style, group: GroupMapLayer, nowMs: Long) {
        if (group.version != seenVersion) {
            seenVersion = group.version
            sync(style, group)
        }
        val serverNow = group.serverNow
        val features = ArrayList<Feature>(tracks.size)
        for ((id, track) in tracks) {
            val samples = group.samples[id]
            val newest = samples?.lastOrNull() ?: continue // nothing heard yet: not on the map
            val frame = if (track.lastFrameMs == 0L) 0.0 else ((nowMs - track.lastFrameMs) / 1000.0).coerceIn(0.0, 0.1)
            track.lastFrameMs = nowMs
            track.delay += (targetDelay(samples) - track.delay).coerceIn(-0.2 * frame, 0.2 * frame)
            val goal = serverNow - track.delay
            var play = track.playTime ?: goal
            // With time, a little faster or slower (0.6× to 1.4×) to stay on the goal: a late
            // position is caught up gently, never with a jump.
            play += frame * (1 + (goal - play) * 0.4).coerceIn(0.6, 1.4)
            // Far off (back from the background, a long silence): straight to the goal.
            if (abs(goal - play) > RESYNC_S) play = goal
            // Never past the last position received: no guessing, so nothing to take back.
            play = min(play, newest.at)
            track.playTime = play
            val (lat, lon, bearing) = pose(track, samples, play)
            if (!track.placed) {
                track.lat = lat
                track.lon = lon
                track.bearing = bearing
                track.placed = true
            } else {
                // The pose is already continuous; this only rounds off the corners.
                track.lat += (lat - track.lat) * SMOOTHING
                track.lon += (lon - track.lon) * SMOOTHING
                track.bearing = lerpAngle(track.bearing, bearing, BEARING_LERP)
            }
            track.moving = newest.speedMps > MIN_SPEED_MPS
            val online = serverNow - newest.at < SILENT_S
            track.online = online
            val key = imageKey(track)
            if (track.imageKey != key) {
                style.addImage(key, memberBitmap(track.member, photos[track.member.avatarUrl]))
                track.imageKey = key
            }
            features += Feature.fromGeometry(Point.fromLngLat(track.lon, track.lat)).apply {
                addStringProperty("id", id)
                addStringProperty("icon", key)
                addStringProperty("arrow", "$ARROW_IMAGE${track.member.colorIndex}")
                addNumberProperty("bearing", track.bearing)
                addBooleanProperty("moving", track.moving)
                addNumberProperty("alpha", if (online) 1f else 0.45f)
            }
        }
        style.getSourceAs<GeoJsonSource>(MEMBERS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Where the camera goes while it follows a member; null when it follows the driver. */
    fun focused(group: GroupMapLayer): Triple<Double, Double, Double>? {
        val track = group.focus?.let { tracks[it] }?.takeIf { it.placed } ?: return null
        return Triple(track.lat, track.lon, track.bearing)
    }

    /** Everyone at once was asked for: the frame holding the driver, the members and their routes. */
    fun takeOverview(group: GroupMapLayer, arrowLat: Double, arrowLon: Double): LatLngBounds? {
        if (group.overviewRequest == seenOverview) return null
        seenOverview = group.overviewRequest
        val points = ArrayList<LatLng>()
        points += LatLng(arrowLat, arrowLon)
        tracks.values.filter { it.placed }.forEach { points += LatLng(it.lat, it.lon) }
        drawnRoutes.values.forEach { route -> route.points.forEach { points += LatLng(it.lat, it.lon) } }
        if (points.size < 2) return null
        return runCatching { LatLngBounds.Builder().includes(points).build() }.getOrNull()
    }

    /** The member under a tap, if any. */
    fun hit(map: MapLibreMap, latLng: LatLng): String? {
        val screen = map.projection.toScreenLocation(latLng)
        return map.queryRenderedFeatures(screen, MEMBERS_LAYER).firstOrNull()?.getStringProperty("id")
    }

    /** Adds, updates and removes the members and their routes, only where something changed. */
    private fun sync(style: Style, group: GroupMapLayer) {
        val fresh = group.members.associateBy { it.id }
        tracks.keys.retainAll(fresh.keys)
        for ((id, member) in fresh) {
            val track = tracks.getOrPut(id) { Track(member) }
            track.member = member
            val route = group.routes[id]
            if (route?.rev != track.routeRev) {
                // Another route: positions are placed on it afresh.
                track.routeRev = route?.rev
                track.path = route?.points?.takeIf { it.size >= 2 }?.let { RoutePath(it) }
                track.alongs.clear()
            }
            member.avatarUrl?.let { url -> if (url !in photos && loading.add(url)) loadPhoto(url) }
        }
        if (group.routes != drawnRoutes) {
            drawnRoutes = group.routes
            val lines = group.routes.values.filter { it.points.size >= 2 }.map { route ->
                Feature.fromGeometry(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addStringProperty("color", String.format("#%06X", 0xFFFFFF and GroupPalette.argb(route.colorIndex)))
                }
            }
            style.getSourceAs<GeoJsonSource>(ROUTES_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        }
    }

    /** Where a member was at [time] (server clock), from the positions around it. */
    private fun pose(track: Track, samples: List<GroupSample>, time: Double): Triple<Double, Double, Double> {
        val first = samples.first()
        val last = samples.last()
        if (time <= first.at || (samples.size == 1 && time <= last.at)) {
            return Triple(first.lat, first.lon, first.bearing ?: track.bearing)
        }
        if (time >= last.at) {
            // The latest news: they are shown there until the next one arrives.
            val path = track.path
            val along = along(track, last)
            if (path != null && along != null && along.isFinite()) {
                val (point, heading) = path.poseAt(along)
                return Triple(point.lat, point.lon, heading)
            }
            return Triple(last.lat, last.lon, last.bearing ?: track.bearing)
        }
        // Between two positions really received.
        var index = samples.size - 2
        while (index > 0 && samples[index].at > time) index -= 1
        val a = samples[index]
        val b = samples[index + 1]
        val t = ((time - a.at) / max(b.at - a.at, 0.001)).coerceIn(0.0, 1.0)
        val path = track.path
        val alongA = along(track, a)
        val alongB = along(track, b)
        if (path != null && alongA != null && alongB != null && alongA.isFinite() && alongB.isFinite()) {
            // Both on the route and in the right order: follow its curves between them.
            val straight = Geo.haversine(a.lat, a.lon, b.lat, b.lon)
            val gap = alongB - alongA
            if (gap >= -5 && gap <= max(straight * 3, 60.0)) {
                val (point, heading) = path.poseAt(alongA + max(gap, 0.0) * t)
                return Triple(point.lat, point.lon, heading)
            }
        }
        val heading = if (Geo.haversine(a.lat, a.lon, b.lat, b.lon) > 3) {
            Geo.bearing(a.lat, a.lon, b.lat, b.lon)
        } else {
            b.bearing ?: track.bearing
        }
        return Triple(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, heading)
    }

    /** How far along the member's route a position sits (NaN off it), worked out once per position. */
    private fun along(track: Track, sample: GroupSample): Double? {
        val path = track.path ?: return null
        track.alongs[sample.at]?.let { return it }
        val match = path.match(sample.lat, sample.lon)
        val value = match?.takeIf { it.offRouteMeters <= ON_ROUTE_M }?.alongMeters ?: Double.NaN
        track.alongs[sample.at] = value
        if (track.alongs.size > 40) {
            track.alongs.keys.sorted().take(track.alongs.size - 20).forEach { track.alongs.remove(it) }
        }
        return value
    }

    /** A little more than the time between two of their positions: there is almost always a next one. */
    private fun targetDelay(samples: List<GroupSample>): Double {
        if (samples.size < 3) return DEFAULT_DELAY_S
        val gaps = samples.zipWithNext { a, b -> b.at - a.at }.takeLast(6).sorted()
        return (gaps[gaps.size / 2] * 1.25 + 0.6).coerceIn(1.5, 9.0)
    }

    private fun imageKey(track: Track): String {
        val photo = if (track.member.avatarUrl != null && photos.containsKey(track.member.avatarUrl)) "p" else "i"
        return "gm-${track.member.id}-${track.member.colorIndex}-$photo"
    }

    private fun loadPhoto(url: String) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        val bytes = r.body?.bytes() ?: return@use null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            loading -= url
            // Loaded: the next frame draws the member with it.
            if (bitmap != null) photos[url] = bitmap
        }
    }

    /**
     * A member on the map: their photo (or initial) in a ring of their colour, the name in a pill
     * underneath. The photo's centre is the image's centre: an empty band on top balances the name.
     */
    private fun memberBitmap(member: GroupMapMember, photo: Bitmap?): Bitmap {
        val d = density
        val ring = RING_DP * d
        val side = PHOTO_DP * d + ring * 2
        val nameBand = NAME_BAND_DP * d
        val width = max(side, WIDTH_DP * d)
        val height = side + nameBand * 2
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val cy = height / 2f
        val color = GroupPalette.argb(member.colorIndex)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x55000000
            setShadowLayer(3 * d, 0f, d, 0x55000000)
        }
        canvas.drawCircle(cx, cy, side / 2f, shadow)
        canvas.drawCircle(cx, cy, side / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val inner = side / 2f - ring
        if (photo != null) {
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = (inner * 2) / min(photo.width, photo.height).toFloat()
            shader.setLocalMatrix(
                Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(cx - photo.width * scale / 2f, cy - photo.height * scale / 2f)
                },
            )
            canvas.drawCircle(cx, cy, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawCircle(cx, cy, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF1F1F1F.toInt() })
            val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFFFFFF.toInt()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = inner * 0.95f
            }
            canvas.drawText(member.name.take(1).uppercase(), cx, cy + letter.textSize * 0.35f, letter)
        }
        // The name, in a dark pill under the photo: readable on both maps.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11 * d
        }
        var name = member.name
        val maxText = width - 10 * d
        while (name.length > 1 && text.measureText(name) > maxText) name = name.dropLast(1)
        if (name != member.name) name = name.dropLast(1) + "…"
        val textWidth = text.measureText(name)
        val top = cy + side / 2f + 3 * d
        val pill = RectF(cx - textWidth / 2f - 5 * d, top, cx + textWidth / 2f + 5 * d, top + nameBand - 4 * d)
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xCC101010.toInt() })
        canvas.drawText(name, cx, pill.centerY() + text.textSize * 0.35f, text)
        return bitmap
    }

    /** A small arrow in the member's colour, pointing up; the layer turns it to their heading. */
    private fun arrowBitmap(color: Int): Bitmap {
        val size = (14 * density).toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(size * 0.5f, size * 0.1f)
            lineTo(size * 0.9f, size * 0.85f)
            lineTo(size * 0.5f, size * 0.65f)
            lineTo(size * 0.1f, size * 0.85f)
            close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.14f
            strokeJoin = Paint.Join.ROUND
            this.color = 0xFFFFFFFF.toInt()
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return bitmap
    }

    private fun lerpAngle(from: Double, to: Double, t: Double): Double {
        val diff = ((to - from + 540.0) % 360.0) - 180.0
        return (from + diff * t + 360.0) % 360.0
    }

    companion object {
        const val MEMBERS_LAYER = "xr-group-members"
        private const val MEMBERS_SOURCE = "xr-group-members-src"
        private const val ARROWS_LAYER = "xr-group-arrows"
        private const val ROUTES_SOURCE = "xr-group-routes-src"
        private const val ROUTES_LAYER = "xr-group-routes"
        private const val ARROW_IMAGE = "gm-arrow-"
        private const val PHOTO_DP = 34f
        private const val RING_DP = 3f
        private const val NAME_BAND_DP = 20f
        private const val WIDTH_DP = 96f
        /** Members are shown this far in the past until their pace of news is known. */
        private const val DEFAULT_DELAY_S = 4.0
        /** Further than this from where it should be, a member's clock jumps instead of catching up. */
        private const val RESYNC_S = 20.0
        private const val SMOOTHING = 0.5
        private const val BEARING_LERP = 0.25
        /** No news this long: the member is drawn faded, where they were last heard of. */
        private const val SILENT_S = 45.0
        /** A member this close to their route is moved along it, not in a straight line. */
        private const val ON_ROUTE_M = 60.0
        private const val MIN_SPEED_MPS = 2.0
    }
}

/** The points of [routes] as one list, for a frame. */
internal fun GroupMapLayer.routePoints(): List<GeoPoint> = routes.values.flatMap { it.points }
