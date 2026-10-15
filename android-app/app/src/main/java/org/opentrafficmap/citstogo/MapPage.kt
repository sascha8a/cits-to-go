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
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.OnLocationStaleListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.opentrafficmap.citstogo.livemap.LiveMap
import org.opentrafficmap.citstogo.livemap.FocusMarker
import org.opentrafficmap.citstogo.livemap.LiveMapFeatures
import org.opentrafficmap.citstogo.livemap.LiveMapLayers
import org.opentrafficmap.citstogo.livemap.LiveMapModel
import org.opentrafficmap.citstogo.livemap.LiveMapSnapshot
import org.opentrafficmap.citstogo.livemap.MapIcons
import org.opentrafficmap.citstogo.livemap.MarkerDetails
import org.opentrafficmap.citstogo.livemap.MarkerDetailsFormat
import org.opentrafficmap.citstogo.livemap.MarkerRef
import org.opentrafficmap.citstogo.livemap.RoadSideUnitHistory
import org.opentrafficmap.citstogo.livemap.RoadSideUnitMarker
import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.srem.SremProfile

private const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val MAP_REFRESH_INTERVAL_MS = 1_000L
private const val FOLLOW_ZOOM = 16.0
private const val FOLLOW_MIN_ZOOM = 12.0
private const val FOLLOW_TRANSITION_MS = 750L
private const val FOCUS_ZOOM = 17.0
// The 48 dp profile icon shrinks to about a 29 dp station marker, and further when zoomed out.
private const val OWN_VEHICLE_MAX_SCALE = 0.6f
private const val OWN_VEHICLE_MIN_SCALE = 0.4f

private data class SavedCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double,
) : Serializable

// Austria, until the first position fix.
private val DEFAULT_CAMERA = SavedCamera(latitude = 47.6, longitude = 13.3, zoom = 6.0, bearing = 0.0)

private val EMPTY_SNAPSHOT = LiveMapSnapshot(emptyList(), emptyList())

private data class MapFrame(val liveMap: LiveMap, val roadSideUnits: List<RoadSideUnitMarker>, val nowMs: Long)

/**
 * Received stations and DENM events on an OpenFreeMap base map. The markers are rebuilt from [snapshot] once per
 * second while the page is resumed. The own position comes from [currentPosition], not from MapLibre. With
 * [historyShown], the map also shows the [roadSideUnits] of the discovery table heard within [historyDays], all of
 * them for 0 days. A [focusStation] of the discovery table gets a pin and its detail card, until the card is closed,
 * another marker is tapped or [onFocusStationCleared] is called; a road side unit the history shows needs no pin.
 * While CAMs go out with [ownVehicleProfile], the own position shows its icon instead of the dot; when they report
 * [ownTrailer], a trailer follows the icon along the last direction of travel.
 */
@Composable
fun MapPage(
    snapshot: LiveMapSnapshot?,
    stationTimeoutMs: Long,
    roadSideUnits: List<DiscoveredStation>,
    historyShown: Boolean,
    onHistoryShownChange: (Boolean) -> Unit,
    historyDays: Int,
    focusStation: DiscoveredStation?,
    onFocusStationCleared: () -> Unit,
    ownVehicleProfile: SremProfile?,
    ownTrailer: Boolean,
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
    var ownPositionStale by remember { mutableStateOf(false) }
    // A standing phone has no heading; the own trailer keeps the last direction of travel, as at a red light.
    var lastOwnHeading by remember { mutableStateOf<Float?>(null) }
    var selected by remember { mutableStateOf<MarkerRef?>(null) }
    var frame by remember { mutableStateOf(MapFrame(LiveMap(emptyList(), emptyList()), emptyList(), 0L)) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestStationTimeoutMs by rememberUpdatedState(stationTimeoutMs)
    val latestRoadSideUnits by rememberUpdatedState(roadSideUnits)
    val latestOnFocusStationCleared by rememberUpdatedState(onFocusStationCleared)
    val latestFocusStation by rememberUpdatedState(focusStation)

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
    val staleListener = remember { OnLocationStaleListener { ownPositionStale = it } }

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
                val tapped = loadedMap
                    .queryRenderedFeatures(
                        loadedMap.projection.toScreenLocation(point),
                        LiveMapLayers.FOCUS,
                        LiveMapLayers.STATIONS,
                        LiveMapLayers.STATION_POINTERS,
                        LiveMapLayers.DENMS,
                        LiveMapLayers.ROAD_SIDE_UNITS,
                    )
                    .firstNotNullOfOrNull(LiveMapFeatures::markerOf)
                if (tapped !is MarkerRef.Focus) latestOnFocusStationCleared()
                selected = tapped
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

    // Restarts on a change of the history, so that the map follows the button at once.
    LaunchedEffect(lifecycleOwner, historyShown, historyDays) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val nowMs = System.currentTimeMillis()
                frame = MapFrame(
                    LiveMapModel.build(latestSnapshot ?: EMPTY_SNAPSHOT, nowMs, latestStationTimeoutMs),
                    if (historyShown) RoadSideUnitHistory.build(latestRoadSideUnits, nowMs, historyDays) else emptyList(),
                    nowMs,
                )
                delay(MAP_REFRESH_INTERVAL_MS)
            }
        }
    }

    // A road side unit that the history shows is marked by its own marker.
    val focusMarker = focusStation
        ?.takeUnless { station -> frame.roadSideUnits.any { it.macAddress == station.macAddress } }
        ?.let(FocusMarker::of)
    LaunchedEffect(style, frame, focusMarker) {
        style?.let { LiveMapLayers.update(it, frame.liveMap, frame.roadSideUnits, focusMarker) }
    }

    // Only a new fix, style or profile may pass the position on again: MapLibre takes it as a fresh fix and would end
    // the stale state of a lost one.
    LaunchedEffect(style, currentPosition, ownVehicleProfile) {
        val loadedMap = map ?: return@LaunchedEffect
        val loadedStyle = style ?: return@LaunchedEffect
        val position = currentPosition ?: return@LaunchedEffect
        val component = loadedMap.locationComponent
        val shown = component.showPosition(
            context,
            loadedStyle,
            position,
            trackingListener,
            staleListener,
            ownVehicleProfile,
        )
        if (shown) locationShown = true
    }

    // After the position effect, so that the location component and its source are in place.
    LaunchedEffect(style, locationShown, currentPosition, ownVehicleProfile, ownTrailer, ownPositionStale) {
        val loadedMap = map ?: return@LaunchedEffect
        val loadedStyle = style ?: return@LaunchedEffect
        if (!locationShown) return@LaunchedEffect
        currentPosition?.movingHeadingDeg?.let { lastOwnHeading = it }
        // Only behind the vehicle icon and along a heading; the grey dot of a stale position has none.
        val trailerHeading = lastOwnHeading
            ?.takeIf { ownVehicleProfile != null && ownTrailer && !ownPositionStale }
        LiveMapLayers.updateOwnTrailer(loadedStyle, trailerHeading, loadedMap.ownVehicleScale())
    }

    LaunchedEffect(locationShown, follow) {
        val loadedMap = map ?: return@LaunchedEffect
        if (locationShown) loadedMap.follow(follow)
    }

    // Once per station: its row keeps updating while the card is open.
    LaunchedEffect(map, focusStation?.macAddress) {
        val loadedMap = map ?: return@LaunchedEffect
        val station = latestFocusStation ?: return@LaunchedEffect
        val pin = FocusMarker.of(station) ?: return@LaunchedEffect
        follow = false
        if (locationShown) loadedMap.follow(false)
        selected = MarkerRef.Focus(station.macAddress)
        loadedMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(pin.latitude, pin.longitude), FOCUS_ZOOM),
            FOLLOW_TRANSITION_MS.toInt(),
        )
    }

    val details = when (val marker = selected) {
        is MarkerRef.Station -> frame.liveMap.stations
            .firstOrNull { it.stationId == marker.stationId }
            ?.let(MarkerDetailsFormat::station)
        is MarkerRef.Denm -> frame.liveMap.denms
            .firstOrNull { it.id == marker.id }
            ?.let { MarkerDetailsFormat.denm(it, frame.nowMs) }
        is MarkerRef.RoadSideUnit -> frame.roadSideUnits
            .firstOrNull { it.macAddress == marker.macAddress }
            ?.let { MarkerDetailsFormat.roadSideUnit(it, frame.nowMs) }
        is MarkerRef.Focus -> focusStation
            ?.takeIf { it.macAddress == marker.macAddress }
            ?.let { MarkerDetailsFormat.discoveredStation(it, frame.nowMs) }
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
        val historyHint = when {
            !historyShown || frame.roadSideUnits.isNotEmpty() -> null
            historyDays > 0 -> pluralStringResource(R.plurals.map_history_none_within_days, historyDays, historyDays)
            else -> stringResource(R.string.map_history_none)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            hint?.let { MapHint(it) }
            historyHint?.let { MapHint(it) }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapToggleButton(
                active = historyShown,
                onToggle = { onHistoryShownChange(!historyShown) },
                icon = Icons.Rounded.History,
                description = stringResource(if (historyShown) R.string.map_history_hide else R.string.map_history_show),
            )
            if (locationShown) {
                MapToggleButton(
                    active = follow,
                    onToggle = { follow = !follow },
                    icon = if (follow) Icons.Rounded.MyLocation else Icons.Rounded.LocationSearching,
                    description = if (follow) "Stop following my position" else "Follow my position",
                )
            }
            details?.let {
                MarkerDetailsCard(
                    it,
                    onClose = {
                        selected = null
                        onFocusStationCleared()
                    },
                )
            }
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
 * Shows [position] as the own location, with an arrow while moving, or with the upright icon of [vehicleProfile]
 * while CAMs go out with it. MapLibre's own location engine stays off.
 * Returns false without location permission; the activity asks for it when the page opens.
 */
private fun LocationComponent.showPosition(
    context: Context,
    style: Style,
    position: DevicePosition,
    trackingListener: OnCameraTrackingChangedListener,
    staleListener: OnLocationStaleListener,
    vehicleProfile: SremProfile?,
): Boolean {
    val permitted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    // The style is outdated while a new one loads after a theme change.
    if (!permitted || !style.isFullyLoaded) return false
    if (!isLocationComponentActivated) {
        activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(ownPositionOptions(context, vehicleProfile))
                .build(),
        )
        addOnCameraTrackingChangedListener(trackingListener)
        addOnLocationStaleListener(staleListener)
    }
    if (!isLocationComponentEnabled) isLocationComponentEnabled = true
    val options = ownPositionOptions(context, vehicleProfile)
    if (locationComponentOptions.foregroundDrawable() != options.foregroundDrawable()) applyStyle(options)
    // The vehicle icon stays upright like the station icons; the moving arrow is for the plain dot only.
    val mode = if (vehicleProfile == null && position.movingHeadingDeg != null) RenderMode.GPS else RenderMode.NORMAL
    if (renderMode != mode) renderMode = mode
    forceLocationUpdate(position.toLocation())
    return true
}

/**
 * MapLibre's blue dot, or the icon of [vehicleProfile] at about the size of a station marker. A position that has
 * gone stale keeps MapLibre's grey dot, so that a lost fix still shows.
 */
private fun ownPositionOptions(context: Context, vehicleProfile: SremProfile?): LocationComponentOptions {
    val builder = LocationComponentOptions.builder(context)
    vehicleProfile?.iconRes()?.let { icon ->
        builder
            .foregroundDrawable(icon)
            .backgroundDrawable(icon)
            .gpsDrawable(icon)
            .maxZoomIconScale(OWN_VEHICLE_MAX_SCALE)
            .minZoomIconScale(OWN_VEHICLE_MIN_SCALE)
    }
    return builder.build()
}

/** The scale MapLibre gives the own vehicle icon at each zoom level, from its [ownPositionOptions]. */
private fun MapLibreMap.ownVehicleScale(): Expression = interpolate(
    linear(),
    zoom(),
    stop(minZoomLevel, OWN_VEHICLE_MIN_SCALE),
    stop(maxZoomLevel, OWN_VEHICLE_MAX_SCALE),
)

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

/** A map button that is filled while its function is on. */
@Composable
private fun MapToggleButton(active: Boolean, onToggle: () -> Unit, icon: ImageVector, description: String) {
    val context = LocalContext.current
    SmallFloatingActionButton(
        onClick = onToggle,
        containerColor = if (active) {
            Color(ContextCompat.getColor(context, R.color.primary))
        } else {
            Color(ContextCompat.getColor(context, R.color.card))
        },
        contentColor = if (active) {
            Color(ContextCompat.getColor(context, R.color.on_primary))
        } else {
            Color(ContextCompat.getColor(context, R.color.primary))
        },
    ) {
        Icon(imageVector = icon, contentDescription = description)
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
