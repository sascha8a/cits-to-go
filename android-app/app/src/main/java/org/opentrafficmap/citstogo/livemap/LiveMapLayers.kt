package org.opentrafficmap.citstogo.livemap

import com.google.gson.JsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** A marker as the feature under a tap identifies it. */
sealed interface MarkerRef {
    data class Station(val stationId: Long) : MarkerRef
    data class Denm(val id: String) : MarkerRef
    data class RoadSideUnit(val macAddress: String) : MarkerRef
}

/** The GeoJSON features of the map layers. Plain GeoJSON, so that JVM tests cover it. */
object LiveMapFeatures {
    const val STATION_ID = "stationId"
    const val DENM_ID = "denmId"
    const val MAC_ADDRESS = "macAddress"
    const val ICON = "icon"
    const val HEADING = "heading"
    const val STALE = "stale"

    // Ids are text: station ids are unsigned 32-bit, and a tap query returns numbers as doubles.
    fun stations(stations: List<StationMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        stations.map { station ->
            val properties = JsonObject().apply {
                addProperty(STATION_ID, station.stationId.toString())
                addProperty(ICON, station.icon)
                station.headingDeg?.let { addProperty(HEADING, it) }
                addProperty(STALE, station.stale)
            }
            Feature.fromGeometry(
                Point.fromLngLat(station.longitude, station.latitude),
                properties,
                station.stationId.toString(),
            )
        },
    )

    fun denms(denms: List<DenmMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        denms.map { denm ->
            val properties = JsonObject().apply {
                addProperty(DENM_ID, denm.id)
                addProperty(ICON, denm.icon)
            }
            Feature.fromGeometry(Point.fromLngLat(denm.longitude, denm.latitude), properties, denm.id)
        },
    )

    fun roadSideUnits(units: List<RoadSideUnitMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        units.map { unit ->
            val properties = JsonObject().apply {
                addProperty(MAC_ADDRESS, unit.macAddress)
                addProperty(ICON, unit.icon)
            }
            Feature.fromGeometry(Point.fromLngLat(unit.longitude, unit.latitude), properties, unit.macAddress)
        },
    )

    fun markerOf(feature: Feature): MarkerRef? = when {
        feature.hasNonNullValueForProperty(DENM_ID) -> MarkerRef.Denm(feature.getStringProperty(DENM_ID))
        feature.hasNonNullValueForProperty(STATION_ID) ->
            feature.getStringProperty(STATION_ID).toLongOrNull()?.let { MarkerRef.Station(it) }
        feature.hasNonNullValueForProperty(MAC_ADDRESS) -> MarkerRef.RoadSideUnit(feature.getStringProperty(MAC_ADDRESS))
        else -> null
    }
}

/** The sources and symbol layers of the live map: road side units from history, stations above them, DENMs on top. */
object LiveMapLayers {
    const val STATIONS = "stations"
    const val DENMS = "denms"
    const val ROAD_SIDE_UNITS = "roadSideUnits"
    private const val STALE_OPACITY = 0.6f
    private const val HISTORY_OPACITY = 0.75f
    private const val OWN_POSITION_LAYER_PREFIX = "mapbox-location-"

    /** Adds the sources and layers once per style; the icons must be added as well, see [MapIcons]. */
    fun addTo(style: Style) {
        if (style.getSource(STATIONS) != null) return
        val stale = eq(get(LiveMapFeatures.STALE), true)
        style.addSource(GeoJsonSource(ROAD_SIDE_UNITS))
        style.addSource(GeoJsonSource(STATIONS))
        style.addSource(GeoJsonSource(DENMS))
        // After a style change MapLibre has already put the own position on the new style; it stays on top.
        val ownPosition = style.layers.firstOrNull { it.id.startsWith(OWN_POSITION_LAYER_PREFIX) }?.id
        fun add(layer: Layer) {
            if (ownPosition != null) style.addLayerBelow(layer, ownPosition) else style.addLayer(layer)
        }
        add(
            SymbolLayer(ROAD_SIDE_UNITS, ROAD_SIDE_UNITS).withProperties(
                iconImage(get(LiveMapFeatures.ICON)),
                iconOpacity(HISTORY_OPACITY),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        add(
            SymbolLayer(STATIONS, STATIONS).withProperties(
                iconImage(
                    switchCase(
                        stale,
                        concat(get(LiveMapFeatures.ICON), literal(MapIcons.STALE_SUFFIX)),
                        get(LiveMapFeatures.ICON),
                    ),
                ),
                iconOpacity(switchCase(stale, literal(STALE_OPACITY), literal(1f))),
                iconRotate(coalesce(get(LiveMapFeatures.HEADING), literal(0f))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        add(
            SymbolLayer(DENMS, DENMS).withProperties(
                iconImage(get(LiveMapFeatures.ICON)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
    }

    fun update(style: Style, liveMap: LiveMap, roadSideUnits: List<RoadSideUnitMarker>) {
        // A style that a newer one replaced must not be touched.
        if (!style.isFullyLoaded) return
        style.getSourceAs<GeoJsonSource>(ROAD_SIDE_UNITS)?.setGeoJson(LiveMapFeatures.roadSideUnits(roadSideUnits))
        style.getSourceAs<GeoJsonSource>(STATIONS)?.setGeoJson(LiveMapFeatures.stations(liveMap.stations))
        style.getSourceAs<GeoJsonSource>(DENMS)?.setGeoJson(LiveMapFeatures.denms(liveMap.denms))
    }
}
