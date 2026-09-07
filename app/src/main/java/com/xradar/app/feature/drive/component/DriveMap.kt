package com.xradar.app.feature.drive.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.LocationSample
import com.xradar.app.core.model.Radar
import com.xradar.app.core.model.UserReport
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
import com.xradar.app.core.geo.RoutePath
import com.xradar.app.core.model.LiveUser
import com.xradar.app.core.model.RadarZone
import com.xradar.app.designsystem.foundation.XRadarIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real map: MapLibre rendering free IGN tiles (satellite ortho or plan). Plots the
 * user's position and nearby radars, and follows the GPS fix **keeping the user's
 * zoom** (recenters gently, north-up) so it never fights manual zoom/pan.
 */
@Composable
fun DriveMap(
    location: LocationSample?,
    radars: List<Radar>,
    reports: List<UserReport>,
    zones: List<RadarZone>,
    liveUsers: List<LiveUser>,
    routePoints: List<GeoPoint>,
    satellite: Boolean,
    following: Boolean,
    onUserGesture: () -> Unit,
    onReportTap: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Compose previews have no GL context — show a plain backdrop instead.
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.fillMaxSize().background(XRadarTheme.colors.canvas))
        return
    }

    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
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

    // Map-matching state: the driver is snapped onto the route so the arrow stays
    // on the line and the passed part gets trimmed away ("eats the line").
    val routePath = remember(routePoints) { if (routePoints.size >= 2) RoutePath(routePoints) else null }
    val routePathState = rememberUpdatedState(routePath)
    val nav = remember { NavHolder() }

    // Per-type map markers, using each type's own icon + color (same as the settings
    // toggles): radar fixe = needle in red, mobile = yellow, caméra, zone, danger…
    // The key is the AlertType name, matched by each feature's "icon" property.
    val colors = XRadarTheme.colors
    val density = LocalDensity.current
    val markerPx = with(density) { 30.dp.roundToPx() }
    val markerSpecs = listOf(
        Triple("RadarFixed", rememberVectorPainter(XRadarIcons.Radar), colors.radarFixed),
        Triple("RadarMobile", rememberVectorPainter(XRadarIcons.Radar), colors.radarMobile),
        Triple("Camera", rememberVectorPainter(XRadarIcons.Camera), colors.radarFixed),
        Triple("ControlZone", rememberVectorPainter(XRadarIcons.Shield), colors.controlZone),
        Triple("Hazard", rememberVectorPainter(XRadarIcons.Warning), colors.hazard),
        Triple("Accident", rememberVectorPainter(XRadarIcons.Accident), colors.hazard),
        Triple("Roadwork", rememberVectorPainter(XRadarIcons.Construction), colors.controlZone),
    )
    val livePainter = rememberVectorPainter(XRadarIcons.Navigation)

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
            // Tapping a report marker (admins can then delete it).
            ready.addOnMapClickListener { latLng ->
                val handler = latestOnReportTap ?: return@addOnMapClickListener false
                val screen = ready.projection.toScreenLocation(latLng)
                val hit = ready.queryRenderedFeatures(screen, REPORT_LAYER).firstOrNull()
                val rid = hit?.getStringProperty("rid")
                if (rid != null) { handler(rid); true } else false
            }
            map = ready
        }
    }

    // Load (or reload) the style whenever the layer choice changes.
    LaunchedEffect(map, satellite) {
        val current = map ?: return@LaunchedEffect
        styleReady = false
        current.setStyle(Style.Builder().fromJson(ignStyleJson(satellite))) { style ->
            // Route (drawn at the bottom, under radars and the user marker).
            style.addSource(GeoJsonSource(ROUTE_SOURCE))
            style.addLayer(
                LineLayer(ROUTE_GLOW, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(ACCENT),
                    PropertyFactory.lineWidth(12f),
                    PropertyFactory.lineOpacity(0.35f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE_CORE, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#3EE1EC"),
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
            // Register a marker image per type (icon + color from the settings visuals).
            markerSpecs.forEach { (key, painter, color) ->
                style.addImage("m-$key", markerBitmap(painter, markerPx, color, density))
            }
            // Other live drivers (distinct violet marker).
            style.addImage(LIVE_IMAGE, markerBitmap(livePainter, markerPx, ComposeColor(0xFF8B7CF6), density))
            style.addSource(GeoJsonSource(LIVE_SOURCE))
            style.addLayer(
                SymbolLayer(LIVE_LAYER, LIVE_SOURCE).withProperties(
                    PropertyFactory.iconImage(LIVE_IMAGE),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.8f),
                ),
            )
            // Radars (drawn under the user marker) — each feature picks its icon.
            style.addSource(GeoJsonSource(RADAR_SOURCE))
            style.addLayer(
                SymbolLayer(RADAR_LAYER, RADAR_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            // Crowdsourced reports, above radars — same per-type icons.
            style.addSource(GeoJsonSource(REPORT_SOURCE))
            style.addLayer(
                SymbolLayer(REPORT_LAYER, REPORT_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            // User position on top: a soft pulsing halo + a heading arrow.
            style.addImage(ARROW_IMAGE, arrowBitmap())
            style.addSource(GeoJsonSource(POSITION_SOURCE))
            style.addLayer(
                CircleLayer(POSITION_HALO, POSITION_SOURCE).withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor(ACCENT),
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
            location?.let { setArrow(style, it.latitude, it.longitude, displayBearing(it, heading, 0f)) }
            setRadars(style, radars)
            setReports(style, reports)
            setZones(style, zones)
            setLive(style, liveUsers)
            setRoute(style, routePoints)
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
        current.style?.let { setReports(it, reports) }
    }

    // Update radar-car zones when they change.
    LaunchedEffect(map, styleReady, zones) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let { setZones(it, zones) }
    }

    // Update live users when they change.
    LaunchedEffect(map, styleReady, liveUsers) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.style?.let { setLive(it, liveUsers) }
    }

    // Match each GPS fix onto the active route (snap + progress); off-route falls back to raw GPS.
    LaunchedEffect(location, routePath) {
        val fix = location ?: return@LaunchedEffect
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
        var arrowLat = locationState.value?.latitude ?: 0.0
        var arrowLon = locationState.value?.longitude ?: 0.0
        var arrowBearing = 0f
        var lastRouteAt = 0L
        var seededArrow = false
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
                }
                val now = System.currentTimeMillis()
                if (rp != null && nav.onRoute) {
                    // Glide the progress toward the matched distance (forward-biased),
                    // place the arrow exactly on the line, and trim what's behind.
                    val delta = nav.targetAlong - displayedAlong
                    displayedAlong += delta * if (delta >= 0) ALONG_LERP else 0.06
                    val (pt, tangent) = rp.poseAt(displayedAlong)
                    arrowLat = pt.lat; arrowLon = pt.lon
                    arrowBearing = lerpAngle(arrowBearing.toDouble(), tangent.toFloat(), 0.5f).toFloat()
                    if (now - lastRouteAt > ROUTE_TRIM_MS) {
                        lastRouteAt = now
                        setRoute(style, rp.trimFrom(displayedAlong))
                    }
                } else {
                    arrowLat += (fix.latitude - arrowLat) * POS_LERP
                    arrowLon += (fix.longitude - arrowLon) * POS_LERP
                    arrowBearing = displayBearing(fix, heading, arrowBearing)
                    if (rp != null && now - lastRouteAt > ROUTE_TRIM_MS) {
                        lastRouteAt = now
                        setRoute(style, rp.points) // show the whole route until we're back on it
                    }
                }
                setArrow(style, arrowLat, arrowLon, arrowBearing)
                val pulse = (sin(phase.toDouble()).toFloat() + 1f) / 2f
                (style.getLayer(POSITION_HALO) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius(16f + 8f * pulse),
                    PropertyFactory.circleOpacity(0.10f + 0.16f * pulse),
                )
                if (followingState.value) {
                    camLat += (arrowLat - camLat) * POS_LERP
                    camLon += (arrowLon - camLon) * POS_LERP
                    camZoom += (NAV_ZOOM - camZoom) * EASE_LERP
                    camTilt += (NAV_TILT - camTilt) * EASE_LERP
                    camBearing = lerpAngle(camBearing, arrowBearing, BEARING_LERP)
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
            phase += 0.18f
            delay(FRAME_MS)
        }
    }
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

/** A crisp navigation chevron pointing up (north), recolored to the accent. */
private fun arrowBitmap(): Bitmap {
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
    // White halo/outline for contrast on satellite imagery.
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = size * 0.09f
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor(ACCENT)
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

private fun setReports(style: Style, reports: List<UserReport>) {
    val features = reports.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addStringProperty("icon", "m-${it.type.alertType.name}")
            addStringProperty("rid", it.id)
        }
    }
    style.getSourceAs<GeoJsonSource>(REPORT_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun setLive(style: Style, users: List<LiveUser>) {
    val features = users.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addNumberProperty("bearing", it.bearingDeg ?: 0f)
        }
    }
    style.getSourceAs<GeoJsonSource>(LIVE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
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

private fun setRoute(style: Style, points: List<GeoPoint>) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    if (points.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        return
    }
    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
    source.setGeoJson(Feature.fromGeometry(line))
}

private fun ignStyleJson(satellite: Boolean): String {
    val layer = if (satellite) "ORTHOIMAGERY.ORTHOPHOTOS" else "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"
    val format = if (satellite) "image/jpeg" else "image/png"
    val url = "https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile" +
        "&LAYER=$layer&STYLE=normal&TILEMATRIXSET=PM&TILEMATRIX={z}&TILECOL={x}&TILEROW={y}&FORMAT=$format"
    return """
        {
          "version": 8,
          "sources": {
            "ign": {
              "type": "raster",
              "tiles": ["$url"],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "© IGN-F/Géoplateforme"
            }
          },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#06070A" } },
            { "id": "ign", "type": "raster", "source": "ign" }
          ]
        }
    """.trimIndent()
}

private const val POSITION_SOURCE = "xr-position"
private const val POSITION_HALO = "xr-position-halo"
private const val POSITION_ARROW = "xr-position-arrow"
private const val ARROW_IMAGE = "xr-arrow"
private const val RADAR_SOURCE = "xr-radars"
private const val RADAR_LAYER = "xr-radars-dot"
private const val REPORT_SOURCE = "xr-reports"
private const val REPORT_LAYER = "xr-reports-dot"
private const val LIVE_SOURCE = "xr-live"
private const val LIVE_LAYER = "xr-live-dot"
private const val LIVE_IMAGE = "m-live"
private const val ZONE_SOURCE = "xr-zones"
private const val ZONE_FILL = "xr-zones-fill"
private const val ZONE_LINE = "xr-zones-line"
private const val ROUTE_SOURCE = "xr-route"
private const val ROUTE_GLOW = "xr-route-glow"
private const val ROUTE_CORE = "xr-route-core"
private const val ACCENT = "#2CD5E0"
private const val NAV_ZOOM = 17.6
private const val NAV_TILT = 45.0
private const val MIN_SPEED_MS = 2f
// Smoothing factors for the follow-camera loop (0..1 per frame) + frame pacing.
private const val POS_LERP = 0.18
private const val EASE_LERP = 0.1
private const val BEARING_LERP = 0.2f
private const val FRAME_MS = 33L
// Map-matching: snap radius, progress smoothing, and how often the trimmed line refreshes.
private const val ON_ROUTE_M = 40.0
private const val ALONG_LERP = 0.22
private const val ROUTE_TRIM_MS = 120L
