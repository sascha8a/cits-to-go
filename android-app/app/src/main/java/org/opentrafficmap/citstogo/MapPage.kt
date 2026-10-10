package org.opentrafficmap.citstogo

import android.Manifest
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.io.Serializable
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.opentrafficmap.citstogo.livemap.LiveMap
import org.opentrafficmap.citstogo.livemap.LiveMapFeatures
import org.opentrafficmap.citstogo.livemap.LiveMapLayers
import org.opentrafficmap.citstogo.livemap.LiveMapModel
import org.opentrafficmap.citstogo.livemap.LiveMapSnapshot
import org.opentrafficmap.citstogo.livemap.MapIcons
import org.opentrafficmap.citstogo.livemap.MarkerDetails
import org.opentrafficmap.citstogo.livemap.MarkerDetailsFormat
import org.opentrafficmap.citstogo.livemap.MarkerRef

private const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val MAP_REFRESH_INTERVAL_MS = 1_000L
private const val FOLLOW_ZOOM = 16.0
private const val FOLLOW_MIN_ZOOM = 12.0
private const val FOLLOW_TRANSITION_MS = 750L

private data class SavedCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double,
) : Serializable

// Austria, until the first position fix.
private val DEFAULT_CAMERA = SavedCamera(latitude = 47.6, longitude = 13.3, zoom = 6.0, bearing = 0.0)

private val EMPTY_SNAPSHOT = LiveMapSnapshot(emptyList(), emptyList())

private data class MapFrame(val liveMap: LiveMap, val nowMs: Long)

/**
 * Received stations and DENM events on an OpenFreeMap base map. The markers are rebuilt from [snapshot] once per
 * second while the page is resumed. The own position comes from [currentPosition], not from MapLibre.
 */
@Composable
fun MapPage(
    snapshot: LiveMapSnapshot?,
    stationTimeoutMs: Long,
    currentPosition: DevicePosition?,
    darkTheme: Boolean,
    receiving: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by rememberSaveable { mutableStateOf(DEFAULT_CAMERA) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var styleFailed by remember { mutableStateOf(false) }
    var locationShown by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MarkerRef?>(null) }
    var frame by remember { mutableStateOf(MapFrame(LiveMap(emptyList(), emptyList()), 0L)) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestStationTimeoutMs by rememberUpdatedState(stationTimeoutMs)

    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(camera.toCameraPosition())
            .logoEnabled(false)
            .attributionEnabled(false)
            .foregroundLoadColor(ContextCompat.getColor(context, R.color.background))
            .setPrefetchZoomDelta(0)
        // Renders into a SurfaceView; should the map flicker in the drawer or on page changes, add .textureMode(true).
        MapView(context, options).apply { onCreate(null) }
    }
    MapViewLifecycle(mapView)

    val trackingListener = remember {
        object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() {
                follow = false
            }

            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { styleFailed = true }
        mapView.getMapAsync { loadedMap ->
            // A new style comes without the icons, sources and layers of the previous one. Not in the callback of
            // setStyle(): MapLibre drops that after a failed load, but retries the download on its own.
            mapView.addOnDidFinishLoadingStyleListener {
                loadedMap.style?.let { loaded ->
                    MapIcons.addTo(loaded, context)
                    LiveMapLayers.addTo(loaded)
                    styleFailed = false
                    style = loaded
                }
            }
            loadedMap.addOnCameraIdleListener {
                loadedMap.cameraPosition.toSavedCamera()?.let { camera = it }
            }
            // Before the own position is shown nothing tracks, so the tracking listener cannot end following.
            loadedMap.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    follow = false
                }

                override fun onMove(detector: MoveGestureDetector) = Unit

                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            loadedMap.addOnMapClickListener { point ->
                selected = loadedMap
                    .queryRenderedFeatures(
                        loadedMap.projection.toScreenLocation(point),
                        LiveMapLayers.STATIONS,
                        LiveMapLayers.DENMS,
                    )
                    .firstNotNullOfOrNull(LiveMapFeatures::markerOf)
                true
            }
            map = loadedMap
        }
    }

    LaunchedEffect(map, darkTheme) {
        val loadedMap = map ?: return@LaunchedEffect
        style = null
        loadedMap.setStyle(Style.Builder().fromUri(if (darkTheme) DARK_STYLE_URL else LIGHT_STYLE_URL))
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val nowMs = System.currentTimeMillis()
                frame = MapFrame(
                    LiveMapModel.build(latestSnapshot ?: EMPTY_SNAPSHOT, nowMs, latestStationTimeoutMs),
                    nowMs,
                )
                delay(MAP_REFRESH_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(style, frame) {
        style?.let { LiveMapLayers.update(it, frame.liveMap) }
    }

    LaunchedEffect(style, currentPosition) {
        val loadedMap = map ?: return@LaunchedEffect
        val loadedStyle = style ?: return@LaunchedEffect
        val position = currentPosition ?: return@LaunchedEffect
        if (loadedMap.locationComponent.showPosition(context, loadedStyle, position, trackingListener)) {
            locationShown = true
        }
    }

    LaunchedEffect(locationShown, follow) {
        val loadedMap = map ?: return@LaunchedEffect
        if (locationShown) loadedMap.follow(follow)
    }

    val details = when (val marker = selected) {
        is MarkerRef.Station -> frame.liveMap.stations
            .firstOrNull { it.stationId == marker.stationId }
            ?.let(MarkerDetailsFormat::station)
        is MarkerRef.Denm -> frame.liveMap.denms
            .firstOrNull { it.id == marker.id }
            ?.let { MarkerDetailsFormat.denm(it, frame.nowMs) }
        null -> null
    }
    LaunchedEffect(selected, details == null) {
        // The marker has disappeared.
        if (details == null) selected = null
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val hint = when {
            styleFailed -> "The map needs a data connection. Open the page again to retry."
            frame.liveMap.stations.isNotEmpty() || frame.liveMap.denms.isNotEmpty() -> null
            receiving -> "Waiting for CAMs and DENMs…"
            else -> "Start a capture or a PCAP replay on Home to see stations and DENMs."
        }
        hint?.let {
            MapHint(
                it,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (locationShown) FollowButton(follow = follow, onToggle = { follow = !follow })
            details?.let { MarkerDetailsCard(it, onClose = { selected = null }) }
            MapAttribution()
        }
    }
}

/** Forwards the activity lifecycle to [mapView] and destroys it when it leaves the composition. */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    mapView.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    mapView.onPause()
                    resumed = false
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    mapView.onStop()
                    started = false
                }
                else -> Unit
            }
        }
        val memoryCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = mapView.onLowMemory()
        }
        lifecycle.addObserver(observer)
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
}

/**
 * Shows [position] as the own location, with an arrow while moving. MapLibre's own location engine stays off.
 * Returns false without location permission; the activity asks for it when the page opens.
 */
private fun LocationComponent.showPosition(
    context: Context,
    style: Style,
    position: DevicePosition,
    trackingListener: OnCameraTrackingChangedListener,
): Boolean {
    val permitted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    // The style is outdated while a new one loads after a theme change.
    if (!permitted || !style.isFullyLoaded) return false
    if (!isLocationComponentActivated) {
        activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .build(),
        )
        addOnCameraTrackingChangedListener(trackingListener)
    }
    if (!isLocationComponentEnabled) isLocationComponentEnabled = true
    val mode = if (position.movingHeadingDeg != null) RenderMode.GPS else RenderMode.NORMAL
    if (renderMode != mode) renderMode = mode
    forceLocationUpdate(position.toLocation())
    return true
}

/** Tracks the own position, zooming in from an overview; a pan on the map ends the tracking. */
private fun MapLibreMap.follow(enabled: Boolean) {
    val component = locationComponent
    if (!enabled) {
        if (component.cameraMode != CameraMode.NONE) component.cameraMode = CameraMode.NONE
        return
    }
    if (component.cameraMode == CameraMode.TRACKING) return
    val zoom = FOLLOW_ZOOM.takeIf { cameraPosition.zoom < FOLLOW_MIN_ZOOM }
    component.setCameraMode(CameraMode.TRACKING, FOLLOW_TRANSITION_MS, zoom, null, null, null)
}

private fun DevicePosition.toLocation(): Location = Location("cits-to-go").apply {
    latitude = latitudeE7 / 10_000_000.0
    longitude = longitudeE7 / 10_000_000.0
    time = timeMs
    accuracyM?.let { accuracy = it }
    speedMetersPerSecond?.let { speed = it }
    movingHeadingDeg?.let { bearing = it }
}

private fun SavedCamera.toCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(LatLng(latitude, longitude))
    .zoom(zoom)
    .bearing(bearing)
    .build()

private fun CameraPosition.toSavedCamera(): SavedCamera? {
    val target = target ?: return null
    return SavedCamera(target.latitude, target.longitude, zoom, bearing)
}

@Composable
private fun FollowButton(follow: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    SmallFloatingActionButton(
        onClick = onToggle,
        containerColor = if (follow) {
            Color(ContextCompat.getColor(context, R.color.primary))
        } else {
            Color(ContextCompat.getColor(context, R.color.card))
        },
        contentColor = if (follow) {
            Color(ContextCompat.getColor(context, R.color.on_primary))
        } else {
            Color(ContextCompat.getColor(context, R.color.primary))
        },
    ) {
        Icon(
            imageVector = if (follow) Icons.Rounded.MyLocation else Icons.Rounded.LocationSearching,
            contentDescription = if (follow) "Stop following my position" else "Follow my position",
        )
    }
}

@Composable
private fun MarkerDetailsCard(details: MarkerDetails, onClose: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    details.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close details",
                        tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            details.rows.forEach { (label, value) ->
                Row(modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        label,
                        modifier = Modifier.width(132.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MapHint(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(
        text,
        modifier = modifier
            .background(Color(ContextCompat.getColor(context, R.color.card)).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
    )
}

/** The attribution OpenFreeMap asks for; the links open in the browser. */
@Composable
private fun MapAttribution() {
    val context = LocalContext.current
    val links = TextLinkStyles(SpanStyle(color = Color(ContextCompat.getColor(context, R.color.primary))))
    val text = buildAnnotatedString {
        withLink(LinkAnnotation.Url("https://openfreemap.org", links)) { append("OpenFreeMap") }
        append(" ")
        withLink(LinkAnnotation.Url("https://www.openmaptiles.org/", links)) { append("© OpenMapTiles") }
        append(" Data from ")
        withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright", links)) { append("OpenStreetMap") }
    }
    Text(
        text,
        modifier = Modifier
            .background(Color(ContextCompat.getColor(context, R.color.card)).copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
    )
}
