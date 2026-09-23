package com.eona.app.feature.drive.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eona.app.BuildConfig
import com.eona.app.core.model.GeoPoint
import com.eona.app.designsystem.theme.EonaTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** One driver on a watching map: where, in which colour, faded when silent. */
data class WatchDot(val lat: Double, val lon: Double, val color: Int, val faded: Boolean = false)

/** One route on a watching map, in its colour (ARGB). */
data class WatchLine(val points: List<GeoPoint>, val color: Int)

/**
 * The map of someone who watches without driving (a shared trip, a group link): the routes, the
 * drivers as dots, the destination as a flag. It frames everything once, when there is something
 * to frame; after that the camera is the viewer's.
 */
@Composable
fun WatchMap(lines: List<WatchLine>, dots: List<WatchDot>, destination: GeoPoint?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = EonaTheme.colors.isDark
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { ready ->
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            map = ready
        }
    }
    LaunchedEffect(map, dark) {
        val current = map ?: return@LaunchedEffect
        style = null
        current.setStyle(Style.Builder().fromJson(PlansMapStyle.json(context, dark, BuildConfig.STADIA_API_KEY))) { loaded ->
            loaded.addImage(FLAG_IMAGE, flagBitmap(context.resources.displayMetrics.density))
            loaded.addSource(GeoJsonSource(LINES))
            loaded.addLayer(
                LineLayer(LINES, LINES).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            loaded.addSource(GeoJsonSource(FLAG))
            loaded.addLayer(
                SymbolLayer(FLAG, FLAG).withProperties(
                    PropertyFactory.iconImage(FLAG_IMAGE),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),
                ),
            )
            loaded.addSource(GeoJsonSource(DOTS))
            loaded.addLayer(
                CircleLayer(DOTS, DOTS).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleOpacity(Expression.get("alpha")),
                    PropertyFactory.circleStrokeOpacity(Expression.get("alpha")),
                ),
            )
            style = loaded
        }
    }
    LaunchedEffect(style, lines, dots, destination) {
        val loaded = style ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(LINES)?.setGeoJson(
            FeatureCollection.fromFeatures(
                lines.filter { it.points.size >= 2 }.map { line ->
                    Feature.fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                        addStringProperty("color", hex(line.color))
                    }
                },
            ),
        )
        loaded.getSourceAs<GeoJsonSource>(DOTS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                dots.map { dot ->
                    Feature.fromGeometry(Point.fromLngLat(dot.lon, dot.lat)).apply {
                        addStringProperty("color", hex(dot.color))
                        addNumberProperty("alpha", if (dot.faded) 0.45f else 1f)
                    }
                },
            ),
        )
        loaded.getSourceAs<GeoJsonSource>(FLAG)?.setGeoJson(
            FeatureCollection.fromFeatures(destination?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) } ?: emptyList()),
        )
        // Framed once: the drivers and where they go, with a margin.
        if (!framed) {
            val points = dots.map { LatLng(it.lat, it.lon) } + listOfNotNull(destination?.let { LatLng(it.lat, it.lon) })
            val current = map ?: return@LaunchedEffect
            when {
                points.size >= 2 -> runCatching {
                    current.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 160))
                    framed = true
                }
                points.size == 1 -> {
                    current.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 13.0))
                    framed = true
                }
            }
        }
    }
}

private fun hex(argb: Int) = String.format("#%06X", 0xFFFFFF and argb)

/** A small flag on a pole: the destination. */
private fun flagBitmap(density: Float): Bitmap {
    val w = (22 * density).toInt()
    val h = (26 * density).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161616.toInt(); strokeWidth = 2.5f * density }
    canvas.drawLine(2 * density, 2 * density, 2 * density, h.toFloat(), pole)
    val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    val path = android.graphics.Path().apply {
        moveTo(3 * density, 2 * density)
        lineTo(w - 2 * density, 7 * density)
        lineTo(3 * density, 13 * density)
        close()
    }
    canvas.drawPath(path, flag)
    return bitmap
}

private const val LINES = "xr-watch-lines"
private const val DOTS = "xr-watch-dots"
private const val FLAG = "xr-watch-flag"
private const val FLAG_IMAGE = "xr-watch-flag-img"
