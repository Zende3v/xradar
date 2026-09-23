package com.eona.app.feature.drive.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.eona.app.BuildConfig
import com.eona.app.core.model.SignType
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.Radar
import com.eona.app.core.model.UserReport
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.designsystem.theme.EonaTheme
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eona.app.R
import com.eona.app.core.geo.Geo
import com.eona.app.core.geo.RoutePath
import com.eona.app.core.model.RadarZone
import com.eona.app.core.model.RoadSign
import com.eona.app.core.model.RouteTraffic
import com.eona.app.core.model.TrafficLevel
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.core.geo.LineSimplifier
import com.eona.app.feature.drive.group.GroupMapLayer
import com.eona.app.feature.drive.group.GroupMapRenderer
import androidx.compose.runtime.rememberCoroutineScope
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.sin

/**
 * Real map: MapLibre rendering the Plans basemap (day or night palette). Plots the
 * user's position and nearby radars, and follows the GPS fix **keeping the user's
 * zoom** (recenters gently, north-up) so it never fights manual zoom/pan.
 */
@Composable
fun DriveMap(
    location: LocationSample?,
    radars: List<Radar>,
    reports: List<UserReport>,
    zones: List<RadarZone>,
    signs: List<RoadSign>,
    routePoints: List<GeoPoint>,
    /** Traffic on the route: its line takes the slowed stretches' colours. */
    traffic: RouteTraffic?,
    following: Boolean,
    onUserGesture: () -> Unit,
    onReportTap: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** The limit under the driver: it decides how close the camera sits (null = town speeds). */
    speedLimitKmh: Int? = null,
    /** The other members of a group trip: read by the map at every frame, never observed. */
    group: GroupMapLayer? = null,
    /** A group member's photo touched: their card. */
    onMemberTap: ((String) -> Unit)? = null,
) {
    // Compose previews have no GL context — show a plain backdrop instead.
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.fillMaxSize().background(EonaTheme.colors.canvas))
        return
    }

    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        // Tiles from earlier drives stay on the phone (MapLibre keeps only 50 MB by default),
        // so a familiar area comes back at once instead of being downloaded again.
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, IgnoreResult)
        MapView(context).apply { onCreate(null) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val latestOnGesture by rememberUpdatedState(onUserGesture)
    val latestOnReportTap by rememberUpdatedState(onReportTap)
    val locationState = rememberUpdatedState(location)
    val followingState = rememberUpdatedState(following)
    val speedLimitState = rememberUpdatedState(speedLimitKmh)
    val groupState = rememberUpdatedState(group)
    val latestOnMemberTap by rememberUpdatedState(onMemberTap)
    val renderScope = rememberCoroutineScope()
    val groupRenderer = remember { GroupMapRenderer(context.resources.displayMetrics.density, renderScope) }

    // Map-matching state: the driver is snapped onto the route so the arrow stays
    // on the line and the passed part gets trimmed away ("eats the line").
    val routePath = remember(routePoints) { if (routePoints.size >= 2) RoutePath(routePoints) else null }
    // What is drawn is lightened (the driver is matched on every point, in [routePath]): a 400 km
    // route of thousands of points was redrawn far too slowly, and every other line waited behind
    // it. The shape stays within a couple of metres.
    val drawnPath = remember(routePoints) {
        if (routePoints.size >= 2) RoutePath(LineSimplifier.simplify(routePoints, ROUTE_DRAW_MAX_POINTS, 2.0)) else null
    }
    val drawnPathState = rememberUpdatedState(drawnPath)
    val routePathState = rememberUpdatedState(routePath)
    val trafficState = rememberUpdatedState(traffic)
    // "Couleur de l'app": the route, the arrow and its halo take it; the style is redrawn with it.
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val accent = 0xFF000000.toInt() or settings.accent.rgb
    val accentState = rememberUpdatedState(accent)
    // Where the drawn line starts along the route (the part driven is cut off), for its colours.
    val routeFrom = remember { DoubleArray(1) }
    val nav = remember { NavHolder() }

    // Per-type map markers, using each type's own icon + color (same as the settings
    // toggles): radar fixe = needle in red, mobile = yellow, caméra, zone, danger…
    // The key is the AlertType name, matched by each feature's "icon" property.
    val colors = EonaTheme.colors
    val density = LocalDensity.current
    val markerPx = with(density) { 30.dp.roundToPx() }
    val clusterAlertPx = with(density) { 34.dp.roundToPx() }
    val clusterSignPx = with(density) { 30.dp.roundToPx() }
    val markerSpecs = listOf(
        Triple("RadarFixed", rememberVectorPainter(EonaIcons.Radar), colors.radarFixed),
        Triple("RadarMobile", rememberVectorPainter(EonaIcons.Radar), colors.radarMobile),
        Triple("Camera", rememberVectorPainter(EonaIcons.Camera), colors.radarFixed),
        Triple("ControlZone", rememberVectorPainter(EonaIcons.Shield), colors.controlZone),
        Triple("Hazard", rememberVectorPainter(EonaIcons.Warning), colors.hazard),
        Triple("Accident", rememberVectorPainter(EonaIcons.Accident), colors.hazard),
        Triple("Roadwork", rememberVectorPainter(EonaIcons.Construction), colors.controlZone),
    )

    // Smooth device heading (rotation-vector sensor), anchored to the GPS bearing
    // so the camera rotates naturally through turns regardless of phone mounting.
    val heading = remember { HeadingHolder() }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val remapped = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Assume an upright (portrait) windshield mount — the common nav case.
                SensorManager.remapCoordinateSystem(
                    rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped,
                )
                SensorManager.getOrientation(remapped, orientation)
                var az = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                heading.azimuth = smoothAngle(heading.azimuth, az)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { ready ->
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = true
            // A user pan/zoom/rotate breaks follow mode (shows the recenter button).
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    latestOnGesture()
                }
            }
            // Tapping a group member (their card), a cluster, or a report marker (admins delete it).
            ready.addOnMapClickListener { latLng ->
                latestOnMemberTap?.let { open ->
                    groupRenderer.hit(ready, latLng)?.let { id ->
                        open(id)
                        return@addOnMapClickListener true
                    }
                }
                val screen = ready.projection.toScreenLocation(latLng)
                val cluster = ready
                    .queryRenderedFeatures(screen, RADAR_CLUSTER, REPORT_CLUSTER, SIGN_CLUSTER)
                    .firstOrNull()
                if (cluster != null) {
                    ready.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            latLng,
                            (ready.cameraPosition.zoom + CLUSTER_ZOOM_STEP).coerceAtMost(CLUSTER_ZOOM_MAX),
                        ),
                        CLUSTER_ZOOM_MS,
                    )
                    return@addOnMapClickListener true
                }
                val handler = latestOnReportTap ?: return@addOnMapClickListener false
                val hit = ready.queryRenderedFeatures(screen, REPORT_LAYER).firstOrNull()
                val rid = hit?.getStringProperty("rid")
                if (rid != null) { handler(rid); true } else false
            }
            map = ready
        }
    }

    // Day or night as the app's theme says ("Thème général"): the map and the HUD over it
    // switch together. The style reloads when it changes.
    val darkMap = EonaTheme.colors.isDark
    LaunchedEffect(map, darkMap, accent) {
        val current = map ?: return@LaunchedEffect
        styleReady = false
        val accentHex = String.format("#%06X", 0xFFFFFF and accent)
        current.setStyle(baseStyle(context, darkMap)) { style ->
            // Route (drawn at the bottom, under radars and the user marker).
            // Line metrics: the traffic colours are laid along the line (line-progress).
            style.addSource(GeoJsonSource(ROUTE_SOURCE, GeoJsonOptions().withLineMetrics(true)))
            style.addLayer(
                LineLayer(ROUTE_GLOW, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(accentHex),
                    PropertyFactory.lineWidth(12f),
                    PropertyFactory.lineOpacity(0.35f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE_CORE, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(accentHex),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            // Radar-car probable zones (translucent circles), above the route.
            val zoneHex = String.format("#%06X", 0xFFFFFF and colors.radarMobile.toArgb())
            style.addSource(GeoJsonSource(ZONE_SOURCE))
            style.addLayer(
                FillLayer(ZONE_FILL, ZONE_SOURCE).withProperties(
                    PropertyFactory.fillColor(zoneHex),
                    PropertyFactory.fillOpacity(0.16f),
                ),
            )
            style.addLayer(
                LineLayer(ZONE_LINE, ZONE_SOURCE).withProperties(
                    PropertyFactory.lineColor(zoneHex),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineOpacity(0.85f),
                ),
            )
            // Custom PNG markers (map display only); vector fallback for the rest.
            val assetMarkers = mapOf(
                "RadarFixed" to R.drawable.marker_radar_fix,
                "RadarMobile" to R.drawable.marker_radar_mobile,
                "Camera" to R.drawable.marker_camera,
                "ControlZone" to R.drawable.marker_zone_controle,
                "Hazard" to R.drawable.marker_danger,
                "Accident" to R.drawable.marker_accident,
                "RadarCar" to R.drawable.marker_voiture_radar,
            )
            // Baseline vector markers for every type (always works)…
            markerSpecs.forEach { (key, painter, color) ->
                style.addImage("m-$key", markerBitmap(painter, markerPx, color, density))
            }
            // …then override with the custom PNGs (guarded so a decode issue can't
            // abort the whole style and blank the map).
            runCatching {
                assetMarkers.forEach { (key, resId) ->
                    BitmapFactory.decodeResource(context.resources, resId)?.let {
                        style.addImage("m-$key", Bitmap.createScaledBitmap(it, markerPx, markerPx, true))
                    }
                }
            }
            // Radars, camera and control: the colour artwork, as supplied; a jam has its own.
            runCatching {
                artworkMarkers.forEach { (key, resId) ->
                    ContextCompat.getDrawable(context, resId)?.let { style.addImage(key, it.toBitmap(markerPx, markerPx)) }
                }
            }
            // Cluster badges (Arthur's icons), scaled to a fixed height. The width that
            // comes out drives where the count sits, so a swapped asset stays aligned.
            val alertBadgeW = registerBadge(style, context, R.drawable.cluster_alert, CLUSTER_ALERT_IMAGE, clusterAlertPx)
            val signBadgeW = registerBadge(style, context, R.drawable.cluster_sign, CLUSTER_SIGN_IMAGE, clusterSignPx)
            val alertOffsetEm = badgeOffsetEm(alertBadgeW, density)
            val signOffsetEm = badgeOffsetEm(signBadgeW, density)
            // OSM road signs — real drawn traffic signs, a bit larger than alerts.
            val signPx = (markerPx * 1.25f).toInt()
            com.eona.app.core.model.SignType.entries
                .filter { it != com.eona.app.core.model.SignType.SpeedLimit }
                .forEach { t -> style.addImage("s-${t.wire}", signBitmap(t, signPx)) }
            // Speed-limit change signs (red ring + number), used along the route.
            SPEED_VALUES.forEach { v -> style.addImage("sp-$v", speedSignBitmap(v, signPx)) }
            style.addSource(GeoJsonSource(SIGN_SOURCE, clusterOptions()))
            style.addLayer(
                SymbolLayer(SIGN_LAYER, SIGN_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.iconSize(1f),
                ).also { it.setFilter(Expression.not(Expression.has(CLUSTER_COUNT))) },
            )
            addBadgeClusterLayer(style, SIGN_SOURCE, SIGN_CLUSTER, CLUSTER_SIGN_IMAGE, signOffsetEm, darkMap)
            setSigns(style, signs)
            // Radars (drawn under the user marker) — each feature picks its icon.
            // Radars: everything is still loaded, but zoomed out they gather into
            // counted bubbles at the centre of each pack, and split apart on zoom in.
            style.addSource(GeoJsonSource(RADAR_SOURCE, clusterOptions()))
            style.addLayer(
                SymbolLayer(RADAR_LAYER, RADAR_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.82f),
                ).also { it.setFilter(Expression.not(Expression.has(CLUSTER_COUNT))) },
            )
            addBadgeClusterLayer(style, RADAR_SOURCE, RADAR_CLUSTER, CLUSTER_ALERT_IMAGE, alertOffsetEm, darkMap)
            // A control zone is a stretch of road, not a dot: draw the 80 m it covers
            // along the reporter's course, under the markers.
            style.addSource(GeoJsonSource(CONTROL_SOURCE))
            style.addLayer(
                LineLayer(CONTROL_LAYER, CONTROL_SOURCE).withProperties(
                    PropertyFactory.lineColor(String.format("#%06X", 0xFFFFFF and colors.controlZone.toArgb())),
                    PropertyFactory.lineWidth(9f),
                    PropertyFactory.lineOpacity(0.65f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
            // Crowdsourced reports, above radars — same per-type icons, same clustering.
            style.addSource(GeoJsonSource(REPORT_SOURCE, clusterOptions()))
            style.addLayer(
                SymbolLayer(REPORT_LAYER, REPORT_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.82f),
                ).also { it.setFilter(Expression.not(Expression.has(CLUSTER_COUNT))) },
            )
            addBadgeClusterLayer(style, REPORT_SOURCE, REPORT_CLUSTER, CLUSTER_ALERT_IMAGE, alertOffsetEm, darkMap)
            // User position on top: a soft pulsing halo + a heading arrow.
            style.addImage(ARROW_IMAGE, arrowBitmap(accent))
            style.addSource(GeoJsonSource(POSITION_SOURCE))
            style.addLayer(
                CircleLayer(POSITION_HALO, POSITION_SOURCE).withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor(accentHex),
                    PropertyFactory.circleOpacity(0.18f),
                ),
            )
            style.addLayer(
                SymbolLayer(POSITION_ARROW, POSITION_SOURCE).withProperties(
                    PropertyFactory.iconImage(ARROW_IMAGE),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.85f),
                ),
            )
            // The group, under the driver's route (their routes) and over the markers (them).
            groupRenderer.install(style, ROUTE_GLOW, POSITION_HALO)
            location?.let { setArrow(style, it.latitude, it.longitude, 0f) }
            setRadars(style, radars)
            setReports(style, reports)
            setControlZones(style, reports)
            setZones(style, zones)
            setRoute(style, drawnPath?.points ?: emptyList())
            routeFrom[0] = 0.0
            applyTraffic(style, drawnPath, 0.0, traffic, accent)
            styleReady = true
        }
    }

    // Update radar markers when the list changes.
    LaunchedEffect(map, styleReady, radars) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let { setRadars(it, radars) }
    }

    // Update report markers when the list changes.
    LaunchedEffect(map, styleReady, reports) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let {
            setReports(it, reports)
            setControlZones(it, reports)
        }
    }

    // Update radar-car zones when they change.
    LaunchedEffect(map, styleReady, zones) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let { setZones(it, zones) }
    }

    // Update road signs when they change.
    LaunchedEffect(map, styleReady, signs) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let { setSigns(it, signs) }
    }

    // Match each GPS fix onto the active route (snap + progress); off-route falls back to raw GPS.
    // New traffic (or a new style) colours the line at once, even when the car stands still.
    LaunchedEffect(traffic, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        if (styleReady) applyTraffic(style, drawnPath, routeFrom[0], traffic, accentState.value)
    }

    LaunchedEffect(location, routePath) {
        val fix = location ?: return@LaunchedEffect
        nav.speedMps = (fix.speedMps ?: 0f).toDouble()
        nav.fixAtMs = System.currentTimeMillis()
        val rp = routePath
        if (rp == null) { nav.onRoute = false; return@LaunchedEffect }
        val m = rp.match(fix.latitude, fix.longitude)
        if (m != null && m.offRouteMeters <= ON_ROUTE_M) {
            nav.targetAlong = m.alongMeters
            nav.onRoute = true
        } else {
            nav.onRoute = false
        }
    }

    // Calibrate the sensor→travel-direction offset from the GPS bearing while moving.
    LaunchedEffect(location) {
        val fix = location ?: return@LaunchedEffect
        val gpsBearing = fix.bearingDeg ?: return@LaunchedEffect
        val az = heading.azimuth ?: return@LaunchedEffect
        if ((fix.speedMps ?: 0f) > MIN_SPEED_MS) {
            val desired = ((gpsBearing.toFloat() - az) % 360f + 360f) % 360f
            heading.offset = if (heading.offsetKnown) smoothAngle(heading.offset, desired, 0.25f) else desired
            heading.offsetKnown = true
        }
    }

    // Single render loop (~30 fps): snapped/smoothed arrow, route-eating, pulse, camera.
    LaunchedEffect(map, styleReady) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        var phase = 0f
        var displayedAlong = 0.0
        var lastRp: RoutePath? = null
        var appliedTraffic: RouteTraffic? = null
        var arrowLat = locationState.value?.latitude ?: 0.0
        var arrowLon = locationState.value?.longitude ?: 0.0
        var arrowBearing = 0f
        var lastRouteAt = 0L
        /** Where the drawn route was last cut (metres along it), -1 while it shows whole. */
        var trimmedAt = -1.0
        var seededArrow = false
        var firstFollow = true
        val startCam = current.cameraPosition
        var camLat = startCam.target?.latitude ?: arrowLat
        var camLon = startCam.target?.longitude ?: arrowLon
        var camBearing = startCam.bearing
        var camZoom = if (startCam.zoom > 1.0) startCam.zoom else NAV_ZOOM
        var camTilt = startCam.tilt

        while (isActive) {
            val style = current.style
            val fix = locationState.value
            if (style != null && fix != null) {
                if (!seededArrow) {
                    arrowLat = fix.latitude; arrowLon = fix.longitude; seededArrow = true
                }
                val rp = routePathState.value
                if (rp !== lastRp) {
                    lastRp = rp
                    displayedAlong = nav.targetAlong
                    if (rp == null) setRoute(style, emptyList())
                    appliedTraffic = null
                    trimmedAt = -1.0
                }
                val drawn = drawnPathState.value
                val now = System.currentTimeMillis()
                if (rp != null && nav.onRoute) {
                    // Dead reckoning: GPS lands once a second, the eye needs sixty. Between
                    // fixes we keep advancing at the last known speed and glide onto the
                    // real position when it arrives — motion stays perfectly continuous.
                    val dt = (now - nav.fixAtMs).coerceIn(0L, MAX_DR_MS) / 1000.0
                    val predicted = nav.targetAlong + nav.speedMps * dt
                    val delta = predicted - displayedAlong
                    displayedAlong += delta * if (delta >= 0) ALONG_LERP else BACK_LERP
                    val (pt, tangent) = rp.poseAt(displayedAlong)
                    arrowLat = pt.lat; arrowLon = pt.lon
                    arrowBearing = lerpAngle(arrowBearing.toDouble(), tangent.toFloat(), TANGENT_LERP).toFloat()
                    // The driven part is cut away by steps of a few dozen metres, hidden under the
                    // arrow: each cut redraws the whole line, so not at every frame.
                    if (drawn != null && now - lastRouteAt > ROUTE_TRIM_MS &&
                        (trimmedAt < 0 || abs(displayedAlong - trimmedAt) >= ROUTE_TRIM_STEP_M || appliedTraffic !== trafficState.value)
                    ) {
                        lastRouteAt = now
                        trimmedAt = displayedAlong
                        // The drawn line is a little shorter than the full one: the same share of it.
                        val drawnAlong = if (rp.totalMeters > 0) displayedAlong * drawn.totalMeters / rp.totalMeters else 0.0
                        val trimmed = drawn.trimFrom(drawnAlong)
                        setRoute(style, trimmed)
                        routeFrom[0] = drawnAlong
                        applyTraffic(style, drawn, drawnAlong, trafficState.value, accentState.value, trimmed)
                        appliedTraffic = trafficState.value
                    }
                } else {
                    // Same trick off-route: project the last fix along its heading.
                    val moving = (fix.speedMps ?: 0f) > MIN_SPEED_MS
                    var tLat = fix.latitude
                    var tLon = fix.longitude
                    val brg = fix.bearingDeg?.toDouble()
                    if (moving && brg != null) {
                        val dt = (now - nav.fixAtMs).coerceIn(0L, MAX_DR_MS) / 1000.0
                        val travelled = nav.speedMps * dt
                        tLat += travelled * cos(Math.toRadians(brg)) / 111_320.0
                        tLon += travelled * sin(Math.toRadians(brg)) /
                            (111_320.0 * cos(Math.toRadians(fix.latitude)))
                    }
                    arrowLat += (tLat - arrowLat) * POS_LERP
                    arrowLon += (tLon - arrowLon) * POS_LERP
                    // North-up when stopped (avoids a wrong compass heading), GPS course when moving.
                    arrowBearing = if (moving) (brg?.toFloat() ?: arrowBearing) else 0f
                    if (drawn != null && now - lastRouteAt > ROUTE_TRIM_MS && (trimmedAt != -2.0 || appliedTraffic !== trafficState.value)) {
                        lastRouteAt = now
                        // The whole route until we're back on it: drawn once, not at every frame.
                        if (trimmedAt != -2.0) setRoute(style, drawn.points)
                        trimmedAt = -2.0
                        routeFrom[0] = 0.0
                        applyTraffic(style, drawn, 0.0, trafficState.value, accentState.value)
                        appliedTraffic = trafficState.value
                    }
                }
                setArrow(style, arrowLat, arrowLon, arrowBearing)
                // The others of the group, where they were a few seconds ago.
                val groupLayer = groupState.value
                if (groupLayer != null) {
                    groupRenderer.step(style, groupLayer, now)
                    groupRenderer.takeOverview(groupLayer, arrowLat, arrowLon)?.let { bounds ->
                        runCatching {
                            current.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, OVERVIEW_PADDING_PX), OVERVIEW_MS)
                        }
                    }
                }
                // Following one member of the group: the camera takes their place, not the driver's.
                val focused = groupLayer?.let { groupRenderer.focused(it) }
                val followLat = focused?.first ?: arrowLat
                val followLon = focused?.second ?: arrowLon
                val followBearing = focused?.third?.toFloat() ?: arrowBearing
                val pulse = (sin(phase.toDouble()).toFloat() + 1f) / 2f
                (style.getLayer(POSITION_HALO) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius(16f + 8f * pulse),
                    PropertyFactory.circleOpacity(0.10f + 0.16f * pulse),
                )
                if (followingState.value) {
                    val targetZoom = navZoom(speedLimitState.value)
                    if (firstFollow) {
                        // Snap on the very first frame so the map opens already upright.
                        firstFollow = false
                        camLat = followLat; camLon = followLon
                        camZoom = targetZoom; camTilt = NAV_TILT
                        camBearing = followBearing.toDouble()
                    }
                    camLat += (followLat - camLat) * POS_LERP
                    camLon += (followLon - camLon) * POS_LERP
                    // The change of distance is eased like the rest: about two seconds, no jump.
                    camZoom += (targetZoom - camZoom) * EASE_LERP
                    camTilt += (NAV_TILT - camTilt) * EASE_LERP
                    camBearing = lerpAngle(camBearing, followBearing, BEARING_LERP)
                    current.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(camLat, camLon)).zoom(camZoom).tilt(camTilt).bearing(camBearing).build(),
                        ),
                    )
                } else {
                    // Track the user's view so re-centering eases from where they left it.
                    val cam = current.cameraPosition
                    camLat = cam.target?.latitude ?: camLat
                    camLon = cam.target?.longitude ?: camLon
                    camBearing = cam.bearing
                    camZoom = cam.zoom
                    camTilt = cam.tilt
                }
            }
            phase += PULSE_STEP
            delay(FRAME_MS)
        }
    }
}

/**
 * How close the camera sits while following. In town the streets follow one another fast and the
 * turns are short: the camera comes closer, so the next junction is readable. On a road limited to
 * 90 and above an exit is decided long before it arrives: the camera keeps the distance it always
 * had. 60, 70, 80 sit in between, so a sign does not make the view jump. No limit known (a lane, a
 * car park, a road the map does not carry): town rules.
 */
private fun navZoom(limitKmh: Int?): Double {
    val distance = when {
        limitKmh == null || limitKmh <= TOWN_KMH -> TOWN_DISTANCE_M
        limitKmh >= FAST_ROAD_KMH -> FAR_DISTANCE_M
        else -> TOWN_DISTANCE_M + (FAR_DISTANCE_M - TOWN_DISTANCE_M) *
            (limitKmh - TOWN_KMH) / (FAST_ROAD_KMH - TOWN_KMH).toDouble()
    }
    // Twice as far is one zoom level out.
    return NAV_ZOOM + ln(FAR_DISTANCE_M / distance) / ln(2.0)
}

/** Resizing the tile cache is best effort: the map works the same if it fails. */
private object IgnoreResult : OfflineManager.FileSourceCallback {
    override fun onSuccess() = Unit
    override fun onError(message: String) = Unit
}

/** Clustering shared by the radar and report sources (alerts only, never road signs). */
private fun clusterOptions(): GeoJsonOptions = GeoJsonOptions()
    .withCluster(true)
    .withClusterRadius(CLUSTER_RADIUS_PX)
    .withClusterMaxZoom(CLUSTER_MAX_ZOOM)

/** Registers a cluster badge scaled to [heightPx]; returns its width in px (0 if absent). */
private fun registerBadge(
    style: Style,
    context: android.content.Context,
    resId: Int,
    name: String,
    heightPx: Int,
): Int = runCatching {
    val src = BitmapFactory.decodeResource(context.resources, resId) ?: return@runCatching 0
    val width = (heightPx * src.width.toFloat() / src.height).toInt().coerceAtLeast(1)
    style.addImage(name, Bitmap.createScaledBitmap(src, width, heightPx, true))
    width
}.getOrDefault(0)

/** Half the badge plus a small gap, in ems of the count's text size. */
private fun badgeOffsetEm(widthPx: Int, density: Density): Float {
    if (widthPx <= 0) return 1.2f
    val widthDp = with(density) { widthPx.toDp().value }
    return (widthDp / 2f + CLUSTER_TEXT_GAP_DP) / CLUSTER_TEXT_SIZE
}

/**
 * A pack of markers: the badge icon with the count set beside it, never on top of it.
 * The number flips between two colours (plus the opposite halo) so it stays readable
 * on both the light and the dark basemap.
 */
private fun addBadgeClusterLayer(
    style: Style,
    source: String,
    layerId: String,
    image: String,
    offsetEm: Float,
    darkMap: Boolean,
) {
    val layer = SymbolLayer(layerId, source).withProperties(
        PropertyFactory.iconImage(image),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.textField(Expression.get("point_count_abbreviated")),
        PropertyFactory.textFont(arrayOf("Stadia Semibold")),
        PropertyFactory.textSize(CLUSTER_TEXT_SIZE),
        PropertyFactory.textColor(if (darkMap) "#FFFFFF" else "#0A0B0D"),
        PropertyFactory.textHaloColor(if (darkMap) "#06070A" else "#FFFFFF"),
        PropertyFactory.textHaloWidth(1.8f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
        PropertyFactory.textOffset(arrayOf(offsetEm, 0f)),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true),
    )
    layer.setFilter(Expression.has(CLUSTER_COUNT))
    style.addLayer(layer)
}

private fun setArrow(style: Style, lat: Double, lon: Double, bearing: Float) {
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    feature.addNumberProperty("bearing", bearing)
    style.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(feature)
}

/** Map-matching state shared between the per-fix matcher and the render loop. */
private class NavHolder {
    @Volatile var targetAlong: Double = 0.0
    @Volatile var onRoute: Boolean = false
    /** Last fix's ground speed and arrival time — the render loop extrapolates from them. */
    @Volatile var speedMps: Double = 0.0
    @Volatile var fixAtMs: Long = 0L
}

/** Fused heading: smoothed sensor azimuth realigned to the GPS travel direction. */
private class HeadingHolder {
    @Volatile var azimuth: Float? = null
    @Volatile var offset: Float = 0f
    @Volatile var offsetKnown: Boolean = false

    fun fused(): Float? {
        val a = azimuth ?: return null
        return ((a + offset) % 360f + 360f) % 360f
    }
}

/** Best available heading: fused sensor first, then GPS bearing, else the fallback. */
private fun displayBearing(fix: LocationSample, heading: HeadingHolder, fallback: Float): Float {
    heading.fused()?.let { return it }
    fix.bearingDeg?.let { return it.toFloat() }
    return fallback
}

/** Low-pass filter for an angle in degrees, handling the 0/360 wrap. */
private fun smoothAngle(prev: Float?, next: Float, alpha: Float = 0.15f): Float {
    if (prev == null) return next
    val diff = ((next - prev + 540f) % 360f) - 180f
    return ((prev + diff * alpha) % 360f + 360f) % 360f
}

/** Shortest-path interpolation from [from] toward [to] by fraction [t]. */
private fun lerpAngle(from: Double, to: Float, t: Float): Double {
    val diff = ((to - from + 540.0) % 360.0) - 180.0
    return (from + diff * t + 360.0) % 360.0
}

/** The colour artwork used as map markers, drawn as supplied (image name → drawable). */
private val artworkMarkers = mapOf(
    "m-RadarFixed" to R.drawable.ic_hud_radar_fixe,
    "m-RadarMobile" to R.drawable.ic_hud_radar_mobile,
    "m-Camera" to R.drawable.ic_hud_camera,
    "m-ControlZone" to R.drawable.ic_hud_zone_controle,
    JAM_MARKER to R.drawable.ic_hud_bouchon,
)

/** A crisp navigation chevron pointing up (north), in the accent ([color], ARGB). */
private fun arrowBitmap(fillColor: Int): Bitmap {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val path = Path().apply {
        moveTo(size * 0.5f, size * 0.12f)   // tip
        lineTo(size * 0.82f, size * 0.86f)  // bottom-right
        lineTo(size * 0.5f, size * 0.68f)   // inner notch
        lineTo(size * 0.18f, size * 0.86f)  // bottom-left
        close()
    }
    // White halo/outline so the arrow reads on any basemap.
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = size * 0.09f
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    })
    return bitmap
}

private fun setRadars(style: Style, radars: List<Radar>) {
    val features = radars.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addStringProperty("icon", "m-${it.alertType.name}")
        }
    }
    style.getSourceAs<GeoJsonSource>(RADAR_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** The 80 m a control zone covers, drawn along the course its reporter had. */
private fun setControlZones(style: Style, reports: List<UserReport>) {
    val features = reports.mapNotNull { r ->
        if (r.type != com.eona.app.core.model.ReportType.ControlZone) return@mapNotNull null
        val bearing = r.bearingDeg ?: return@mapNotNull null
        val half = CONTROL_ZONE_LENGTH_M / 2.0
        val dLat = half * cos(Math.toRadians(bearing)) / 111_320.0
        val dLon = half * sin(Math.toRadians(bearing)) /
            (111_320.0 * cos(Math.toRadians(r.lat)).coerceAtLeast(0.1))
        Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(r.lon - dLon, r.lat - dLat),
                    Point.fromLngLat(r.lon + dLon, r.lat + dLat),
                ),
            ),
        )
    }
    style.getSourceAs<GeoJsonSource>(CONTROL_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun setReports(style: Style, reports: List<UserReport>) {
    val features = reports.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            // A jam has its own artwork; the other reports share their alert's marker.
            addStringProperty("icon", if (it.type == com.eona.app.core.model.ReportType.TrafficJam) JAM_MARKER else "m-${it.type.alertType.name}")
            addStringProperty("rid", it.id)
        }
    }
    style.getSourceAs<GeoJsonSource>(REPORT_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

// ---- Drawn traffic signs (French/EU look) ----------------------------------
private const val SIGN_RED = 0xFFD22B2B.toInt()
private const val SIGN_BLUE = 0xFF1F5AA8.toInt()
private const val SIGN_YELLOW = 0xFFF6C700.toInt()
private const val SIGN_WHITE = 0xFFFFFFFF.toInt()
private const val SIGN_BLACK = 0xFF161616.toInt()

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
}

/** Draw a recognizable French road sign into a square bitmap. */
private fun signBitmap(type: SignType, sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = sizePx.toFloat()
    val cx = s / 2f
    val cy = s / 2f
    val r = s * 0.44f
    when (type) {
        SignType.Stop -> {
            val p = polygon(cx, cy, r, 8, -22.5)
            c.drawPath(p, fill(SIGN_RED))
            c.drawPath(p, stroke(SIGN_WHITE, s * 0.05f))
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SIGN_WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = s * 0.26f
            }
            c.drawText("STOP", cx, cy + tp.textSize * 0.36f, tp)
        }
        SignType.GiveWay -> {
            val p = triangleDown(cx, cy, r * 1.05f)
            c.drawPath(p, fill(SIGN_WHITE))
            c.drawPath(p, stroke(SIGN_RED, s * 0.11f))
        }
        SignType.LevelCrossing -> {
            // Croix de Saint-André: two white arms with a red edge, on a white disc
            // so it reads on both basemaps.
            c.drawCircle(cx, cy, r, fill(SIGN_WHITE))
            c.drawCircle(cx, cy, r, stroke(SIGN_RED, s * 0.06f))
            val arm = r * 0.78f
            val edge = stroke(SIGN_RED, s * 0.16f)
            val core = stroke(SIGN_WHITE, s * 0.08f)
            c.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, edge)
            c.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, edge)
            c.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, core)
            c.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, core)
        }
        SignType.NoEntry -> {
            c.drawCircle(cx, cy, r, fill(SIGN_RED))
            c.drawCircle(cx, cy, r, stroke(SIGN_WHITE, s * 0.03f))
            val bar = RectF(cx - r * 0.55f, cy - r * 0.17f, cx + r * 0.55f, cy + r * 0.17f)
            c.drawRoundRect(bar, s * 0.03f, s * 0.03f, fill(SIGN_WHITE))
        }
        SignType.Roundabout -> {
            c.drawCircle(cx, cy, r, fill(SIGN_BLUE))
            val ap = stroke(SIGN_WHITE, s * 0.08f)
            val ring = r * 0.42f
            for (k in 0..2) {
                val a = Math.toRadians(90.0 + k * 120.0)
                val bx = cx + (ring * cos(a)).toFloat(); val by = cy + (ring * sin(a)).toFloat()
                val a2 = a + Math.toRadians(70.0)
                val ex = cx + (ring * cos(a2)).toFloat(); val ey = cy + (ring * sin(a2)).toFloat()
                c.drawLine(bx, by, ex, ey, ap)
                c.drawPath(arrowHead(ex, ey, Math.toDegrees(a2).toFloat() + 90f, s * 0.11f), fill(SIGN_WHITE))
            }
        }
        SignType.Crossing -> {
            val sq = RectF(cx - r, cy - r, cx + r, cy + r)
            c.drawRoundRect(sq, s * 0.08f, s * 0.08f, fill(SIGN_BLUE))
            // white pedestrian stripes + a simple walking figure
            val wp = fill(SIGN_WHITE)
            c.drawCircle(cx, cy - r * 0.42f, r * 0.16f, wp) // head
            val body = Path().apply {
                moveTo(cx, cy - r * 0.24f); lineTo(cx, cy + r * 0.18f)
                moveTo(cx, cy - r * 0.10f); lineTo(cx + r * 0.28f, cy + r * 0.02f)
                moveTo(cx, cy - r * 0.10f); lineTo(cx - r * 0.22f, cy + r * 0.04f)
                moveTo(cx, cy + r * 0.18f); lineTo(cx + r * 0.24f, cy + r * 0.5f)
                moveTo(cx, cy + r * 0.18f); lineTo(cx - r * 0.20f, cy + r * 0.5f)
            }
            c.drawPath(body, stroke(SIGN_WHITE, s * 0.055f))
        }
        SignType.Construction -> {
            val p = triangleUp(cx, cy, r * 1.05f)
            c.drawPath(p, fill(SIGN_YELLOW))
            c.drawPath(p, stroke(SIGN_RED, s * 0.09f))
            // simplified worker + mound
            val bp = stroke(SIGN_BLACK, s * 0.05f)
            c.drawCircle(cx - r * 0.1f, cy - r * 0.05f, r * 0.11f, fill(SIGN_BLACK)) // head
            c.drawLine(cx - r * 0.1f, cy + r * 0.05f, cx - r * 0.1f, cy + r * 0.32f, bp)
            c.drawLine(cx - r * 0.1f, cy + r * 0.12f, cx + r * 0.28f, cy - r * 0.12f, bp) // shovel arm
            val mound = Path().apply { moveTo(cx - r * 0.35f, cy + r * 0.42f); lineTo(cx + r * 0.02f, cy + r * 0.42f); lineTo(cx - r * 0.16f, cy + r * 0.28f); close() }
            c.drawPath(mound, fill(SIGN_BLACK))
        }
        SignType.TrafficSignals -> {
            val box = RectF(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy + r)
            c.drawRoundRect(box, s * 0.06f, s * 0.06f, fill(SIGN_BLACK))
            val rr = r * 0.22f
            c.drawCircle(cx, cy - r * 0.5f, rr, fill(0xFFE23B3B.toInt()))
            c.drawCircle(cx, cy, rr, fill(0xFFF3A825.toInt()))
            c.drawCircle(cx, cy + r * 0.5f, rr, fill(0xFF2FBF57.toInt()))
        }
        SignType.SpeedLimit -> { // rendered via speedSignBitmap; fallback ring
            c.drawCircle(cx, cy, r, fill(SIGN_WHITE))
            c.drawCircle(cx, cy, r - s * 0.07f, stroke(SIGN_RED, s * 0.12f))
        }
    }
    return bmp
}

private fun polygon(cx: Float, cy: Float, r: Float, sides: Int, startDeg: Double): Path = Path().apply {
    for (i in 0 until sides) {
        val a = Math.toRadians(startDeg + 360.0 * i / sides)
        val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
private fun triangleDown(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx - r, cy - r * 0.7f); lineTo(cx + r, cy - r * 0.7f); lineTo(cx, cy + r * 0.85f); close()
}
private fun triangleUp(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx, cy - r * 0.85f); lineTo(cx + r, cy + r * 0.7f); lineTo(cx - r, cy + r * 0.7f); close()
}
private fun arrowHead(x: Float, y: Float, dirDeg: Float, size: Float): Path = Path().apply {
    val a = Math.toRadians(dirDeg.toDouble())
    val la = a + Math.toRadians(140.0); val ra = a - Math.toRadians(140.0)
    moveTo(x, y)
    lineTo(x + (size * cos(la)).toFloat(), y + (size * sin(la)).toFloat())
    lineTo(x + (size * cos(ra)).toFloat(), y + (size * sin(ra)).toFloat())
    close()
}

private val SPEED_VALUES = com.eona.app.core.model.SpeedLimits.VALUES
private fun snapSpeed(v: Int?): Int {
    val s = v ?: 50
    return SPEED_VALUES.minByOrNull { kotlin.math.abs(it - s) } ?: 50
}

private fun setSigns(style: Style, signs: List<RoadSign>) {
    val features = signs.map {
        val icon = if (it.type == SignType.SpeedLimit) "sp-${snapSpeed(it.speed)}" else "s-${it.type.wire}"
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("icon", icon) }
    }
    style.getSourceAs<GeoJsonSource>(SIGN_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Round French speed-limit sign: white disc, red ring, black number. */
private fun speedSignBitmap(v: Int, sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = sizePx / 2f
    val r = sizePx * 0.46f
    c.drawCircle(cx, cx, r, fill(SIGN_WHITE))
    c.drawCircle(cx, cx, r - sizePx * 0.07f, stroke(SIGN_RED, sizePx * 0.13f))
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SIGN_BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        textSize = sizePx * (if (v >= 100) 0.40f else 0.46f)
    }
    c.drawText(v.toString(), cx, cx + tp.textSize * 0.35f, tp)
    return bmp
}

private fun setZones(style: Style, zones: List<RadarZone>) {
    val features = zones.map { Feature.fromGeometry(circlePolygon(it.lat, it.lon, it.radiusMeters)) }
    style.getSourceAs<GeoJsonSource>(ZONE_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** A geodesic circle (approximated by a 64-gon) around a point, in metres. */
private fun circlePolygon(lat: Double, lon: Double, radiusM: Double, steps: Int = 64): Polygon {
    val earth = 6371000.0
    val latRad = Math.toRadians(lat)
    val ring = (0..steps).map { i ->
        val theta = 2.0 * PI * i / steps
        val dLat = Math.toDegrees(radiusM * cos(theta) / earth)
        val dLon = Math.toDegrees(radiusM * sin(theta) / (earth * cos(latRad)))
        Point.fromLngLat(lon + dLon, lat + dLat)
    }
    return Polygon.fromLngLats(listOf(ring))
}

/** Rasterize an icon into a round map marker: white chip + colored ring + colored glyph. */
private fun markerBitmap(painter: Painter, sizePx: Int, iconColor: ComposeColor, density: Density): Bitmap {
    val image = ImageBitmap(sizePx, sizePx)
    val canvas = ComposeCanvas(image)
    val size = Size(sizePx.toFloat(), sizePx.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size) {
        val ring = 2.dp.toPx()
        drawCircle(ComposeColor.White, radius = size.minDimension / 2f - ring / 2f, center = center)
        drawCircle(iconColor, radius = size.minDimension / 2f - ring / 2f, center = center, style = Stroke(width = ring))
        val iconPx = size.minDimension * 0.56f
        val pad = (size.minDimension - iconPx) / 2f
        translate(pad, pad) {
            with(painter) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(iconColor)) }
        }
    }
    return image.asAndroidBitmap()
}

/**
 * The route line's colours along it: its own ([routeColor], the app's colour), and the traffic's
 * where the road is slowed (amber, orange, red, dark red when closed), blended over
 * [TRAFFIC_BLEND_M]. [fromM]: where the drawn line ([drawn], [rp] cut there) starts along [rp].
 * The backend measured the same points; its metres are scaled to [rp]'s. Clear road: plain colour.
 */
private fun applyTraffic(
    style: Style,
    rp: RoutePath?,
    fromM: Double,
    traffic: RouteTraffic?,
    routeColor: Int,
    drawn: List<GeoPoint>? = null,
) {
    val layer = style.getLayerAs<LineLayer>(ROUTE_CORE) ?: return
    val stops = ArrayList<Pair<Double, Int>>()
    stops += 0.0 to routeColor
    if (rp != null && traffic != null && traffic.totalMeters > 0 && rp.totalMeters - fromM > 1) {
        val line = LineMeasure(drawn ?: rp.trimFrom(fromM))
        val scale = rp.totalMeters / traffic.totalMeters
        var cursor = 0.0
        for (stretch in traffic.stretches.sortedBy { it.fromMeters }) {
            val from = line.progress(stretch.fromMeters * scale - fromM)
            val to = line.progress(stretch.toMeters * scale - fromM)
            if (to <= from || to <= 0.0 || from < cursor) continue
            val color = trafficColor(stretch.level)
            val blendIn = line.progress(stretch.fromMeters * scale - fromM - TRAFFIC_BLEND_M)
            val blendOut = line.progress(stretch.toMeters * scale - fromM + TRAFFIC_BLEND_M)
            stops += maxOf(blendIn, cursor) to routeColor
            stops += from to color
            stops += to to color
            stops += blendOut to routeColor
            cursor = blendOut
        }
    }
    stops += 1.0 to routeColor
    // line-progress stops must go strictly up.
    val kept = ArrayList<Pair<Double, Int>>()
    for (stop in stops) if (kept.isEmpty() || stop.first > kept.last().first + 1e-6) kept += stop
    layer.setProperties(
        PropertyFactory.lineGradient(
            Expression.interpolate(
                Expression.linear(),
                Expression.lineProgress(),
                *kept.map { (at, color) -> Expression.stop(at.toFloat(), Expression.color(color)) }.toTypedArray(),
            ),
        ),
    )
}

private fun trafficColor(level: TrafficLevel): Int = when (level) {
    TrafficLevel.Slow -> 0xFFFFB300.toInt()
    TrafficLevel.Jam -> 0xFFFF6D00.toInt()
    TrafficLevel.Heavy -> 0xFFE53935.toInt()
    TrafficLevel.Closed -> 0xFF8E1B1B.toInt()
}

/**
 * A drawn line's length both ways: in metres, and as MapLibre's line-progress measures it (web
 * Mercator), so a distance along the road lands at the right place of the gradient.
 */
private class LineMeasure(points: List<GeoPoint>) {
    private val meters = DoubleArray(points.size)
    private val mercator = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            meters[i] = meters[i - 1] + Geo.haversine(a.lat, a.lon, b.lat, b.lon)
            mercator[i] = mercator[i - 1] + hypot(mercX(b.lon) - mercX(a.lon), mercY(b.lat) - mercY(a.lat))
        }
    }

    /** The line-progress (0…1) [m] metres along the line. */
    fun progress(m: Double): Double {
        val total = meters.lastOrNull() ?: 0.0
        val mercTotal = mercator.lastOrNull() ?: 0.0
        if (total <= 0.0 || mercTotal <= 0.0) return 0.0
        val d = m.coerceIn(0.0, total)
        var lo = 0
        var hi = meters.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (meters[mid] <= d) lo = mid else hi = mid
        }
        val span = meters[hi] - meters[lo]
        val t = if (span > 0) (d - meters[lo]) / span else 0.0
        return (mercator[lo] + t * (mercator[hi] - mercator[lo])) / mercTotal
    }

    private fun mercX(lon: Double) = lon / 360.0
    private fun mercY(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / (2 * Math.PI)
}

private fun setRoute(style: Style, points: List<GeoPoint>) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    if (points.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        return
    }
    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
    source.setGeoJson(Feature.fromGeometry(line))
}

/**
 * The basemap under the app layers: our Apple-Plans-like style ([PlansMapStyle]), day or
 * night palette, drawn over Stadia's vector tiles. Needs STADIA_API_KEY (see GUIDE §10).
 */
private fun baseStyle(context: Context, dark: Boolean): Style.Builder =
    Style.Builder().fromJson(PlansMapStyle.json(context, dark, BuildConfig.STADIA_API_KEY))

private const val POSITION_SOURCE = "xr-position"
private const val POSITION_HALO = "xr-position-halo"
private const val POSITION_ARROW = "xr-position-arrow"
private const val ARROW_IMAGE = "xr-arrow"
private const val CLUSTER_ALERT_IMAGE = "xr-cluster-alert"
private const val CLUSTER_SIGN_IMAGE = "xr-cluster-sign"
private const val RADAR_SOURCE = "xr-radars"
private const val RADAR_LAYER = "xr-radars-dot"
private const val RADAR_CLUSTER = "xr-radars-cluster"
private const val CONTROL_SOURCE = "xr-control-zones"
private const val CONTROL_LAYER = "xr-control-zones-line"
/** How much of the lane a reported control zone covers. */
private const val CONTROL_ZONE_LENGTH_M = 80.0
private const val REPORT_SOURCE = "xr-reports"
private const val REPORT_LAYER = "xr-reports-dot"
private const val REPORT_CLUSTER = "xr-reports-cluster"
private const val SIGN_SOURCE = "xr-signs"
private const val SIGN_LAYER = "xr-signs-dot"
private const val SIGN_CLUSTER = "xr-signs-cluster"
private const val MARKER_MIN_ZOOM = 9.5f
private const val ZONE_SOURCE = "xr-zones"
private const val ZONE_FILL = "xr-zones-fill"
private const val ZONE_LINE = "xr-zones-line"
private const val ROUTE_SOURCE = "xr-route"
private const val ROUTE_GLOW = "xr-route-glow"
private const val ROUTE_CORE = "xr-route-core"
/** How far the traffic colours blend into the route's own colour. */
private const val TRAFFIC_BLEND_M = 25.0
/** The marker of an "Embouteillage" report. */
private const val JAM_MARKER = "m-jam"
/** The zoom on a road limited to [FAST_ROAD_KMH] or more (the camera's far distance). */
private const val NAV_ZOOM = 17.6
/** Camera distances as on iOS: far on fast roads, closer in town. */
private const val FAR_DISTANCE_M = 650.0
private const val TOWN_DISTANCE_M = 380.0
private const val TOWN_KMH = 50
private const val FAST_ROAD_KMH = 90
private const val NAV_TILT = 45.0
private const val MIN_SPEED_MS = 2f
// Smoothing factors for the follow-camera loop (0..1 per frame) + frame pacing.
// Follow factors are tuned for the 60 fps loop below (halved from the 30 fps values).
private const val POS_LERP = 0.10
private const val EASE_LERP = 0.06
private const val BEARING_LERP = 0.12f
private const val TANGENT_LERP = 0.3f
private const val PULSE_STEP = 0.09f
private const val FRAME_MS = 16L
// Map-matching: snap radius, progress smoothing, and how often the trimmed line refreshes.
private const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
// Alert clustering: group below this zoom, within this many screen pixels.
private const val CLUSTER_MAX_ZOOM = 13
private const val CLUSTER_RADIUS_PX = 62
private const val CLUSTER_COUNT = "point_count"
private const val CLUSTER_ZOOM_STEP = 1.8
private const val CLUSTER_ZOOM_MAX = 16.5
private const val CLUSTER_ZOOM_MS = 500
// The count sits to the right of the badge; the offset is derived from the badge's
// own width (see badgeOffsetEm) so each icon gets the gap it actually needs.
private const val CLUSTER_TEXT_SIZE = 14f
private const val CLUSTER_TEXT_GAP_DP = 4f
private const val ON_ROUTE_M = 40.0
private const val ALONG_LERP = 0.12
private const val BACK_LERP = 0.04
/** Never dead-reckon further than this past the last fix (GPS lost, tunnel…). */
private const val MAX_DR_MS = 2_500L
private const val ROUTE_TRIM_MS = 120L
/** The driven part is cut away by steps of this many metres: fewer redraws of the line. */
private const val ROUTE_TRIM_STEP_M = 30.0
/** The driver's own route is drawn with this many points at most. */
private const val ROUTE_DRAW_MAX_POINTS = 2500
/** "Tous": the whole group framed with this margin, in this time. */
private const val OVERVIEW_PADDING_PX = 160
private const val OVERVIEW_MS = 800
