package org.opentrafficmap.citstogo.livemap

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.opentrafficmap.citstogo.station.DiscoveredStation

class LiveMapFeaturesTest {
    private val car = StationMarker(
        stationId = 0xFFFF_FFFEL,
        stationType = 5,
        typeLabel = "Passenger car",
        latitude = 48.2024036,
        longitude = 16.3691773,
        headingDeg = 135.5,
        speedMps = 13.89,
        trailer = true,
        icon = "station-car",
        ageMs = 3_000,
        stale = true,
    )
    private val roadworks = DenmMarker(
        id = "1021000/7",
        originatingStationId = 1_021_000,
        stationType = 15,
        stationTypeLabel = "Road-side unit",
        latitude = 48.1,
        longitude = 16.2,
        label = "Short-term stationary roadworks",
        icon = "denm-roadworks",
        causeCode = 3,
        subCauseCode = 4,
        informationQuality = 2,
        infrastructure = true,
        sinceDetectionMs = 1_000,
        sinceReceivedMs = 1_000,
        expiresAtMs = 0,
    )
    private val trafficLight = RoadSideUnitMarker(
        macAddress = "02:42:42:00:00:01",
        latitude = 48.1234567,
        longitude = 16.2345678,
        trafficLight = true,
        icon = RoadSideUnitHistory.TRAFFIC_LIGHT_ICON,
        firstSeenMs = 1_000L,
        lastSeenMs = 2_000L,
        messageCounts = emptyMap(),
        lastRssiDbm = null,
        bestRssiDbm = null,
    )

    @Test
    fun stationBecomesAPointWithItsProperties() {
        val feature = LiveMapFeatures.stations(listOf(car)).features()!!.single()

        assertEquals("4294967294", feature.id())
        assertEquals(Point.fromLngLat(16.3691773, 48.2024036), feature.geometry())
        assertEquals("4294967294", feature.getStringProperty(LiveMapFeatures.STATION_ID))
        assertEquals("station-car", feature.getStringProperty(LiveMapFeatures.ICON))
        assertEquals(135.5, feature.getNumberProperty(LiveMapFeatures.HEADING).toDouble(), 0.0)
        assertTrue(feature.getBooleanProperty(LiveMapFeatures.TRAILER))
        assertTrue(feature.getBooleanProperty(LiveMapFeatures.STALE))
        assertFalse(feature.hasProperty(LiveMapFeatures.DENM_ID))
    }

    @Test
    fun stationWithoutHeadingGetsNoPointer() {
        val noHeading = car.copy(headingDeg = null, trailer = false, stale = false)

        val feature = LiveMapFeatures.stations(listOf(noHeading)).features()!!.single()

        assertFalse(feature.hasProperty(LiveMapFeatures.HEADING))
        assertEquals("station-car", feature.getStringProperty(LiveMapFeatures.ICON))
        assertFalse(feature.getBooleanProperty(LiveMapFeatures.TRAILER))
        assertFalse(feature.getBooleanProperty(LiveMapFeatures.STALE))
    }

    @Test
    fun denmBecomesAPointAtItsEventPosition() {
        val feature = LiveMapFeatures.denms(listOf(roadworks)).features()!!.single()

        assertEquals("1021000/7", feature.id())
        assertEquals(Point.fromLngLat(16.2, 48.1), feature.geometry())
        assertEquals("1021000/7", feature.getStringProperty(LiveMapFeatures.DENM_ID))
        assertEquals("denm-roadworks", feature.getStringProperty(LiveMapFeatures.ICON))
        assertFalse(feature.hasProperty(LiveMapFeatures.STATION_ID))
    }

    @Test
    fun roadSideUnitBecomesAPointWithItsAddressAndIcon() {
        val feature = LiveMapFeatures.roadSideUnits(listOf(trafficLight)).features()!!.single()

        assertEquals("02:42:42:00:00:01", feature.id())
        assertEquals(Point.fromLngLat(16.2345678, 48.1234567), feature.geometry())
        assertEquals("02:42:42:00:00:01", feature.getStringProperty(LiveMapFeatures.MAC_ADDRESS))
        assertEquals(RoadSideUnitHistory.TRAFFIC_LIGHT_ICON, feature.getStringProperty(LiveMapFeatures.ICON))
        assertFalse(feature.hasProperty(LiveMapFeatures.STATION_ID))
    }

    @Test
    fun focusIsAPinAtTheStationPosition() {
        val station = DiscoveredStation(macAddress = "02:42:42:00:00:05", latitudeE7 = 481_234_567, longitudeE7 = 162_345_678)
        val pin = requireNotNull(FocusMarker.of(station))

        val feature = LiveMapFeatures.focus(pin).features()!!.single()

        assertEquals(Point.fromLngLat(16.2345678, 48.1234567), feature.geometry())
        assertEquals("02:42:42:00:00:05", feature.getStringProperty(LiveMapFeatures.FOCUS_MAC_ADDRESS))
        assertEquals(0, LiveMapFeatures.focus(null).features()!!.size)
        assertNull(FocusMarker.of(station.copy(latitudeE7 = null)))
        assertEquals(
            MarkerRef.Focus("02:42:42:00:00:05"),
            LiveMapFeatures.markerOf(Feature.fromJson(feature.toJson())),
        )
    }

    @Test
    fun emptyMapHasNoFeatures() {
        assertEquals(0, LiveMapFeatures.stations(emptyList()).features()!!.size)
        assertEquals(0, LiveMapFeatures.denms(emptyList()).features()!!.size)
        assertEquals(0, LiveMapFeatures.roadSideUnits(emptyList()).features()!!.size)
    }

    @Test
    fun tappedFeaturesNameTheirMarker() {
        // A tap query returns the features as GeoJSON, with numbers as doubles.
        fun tapped(collection: FeatureCollection): Feature = Feature.fromJson(collection.features()!!.single().toJson())

        assertEquals(
            MarkerRef.Station(0xFFFF_FFFEL),
            LiveMapFeatures.markerOf(tapped(LiveMapFeatures.stations(listOf(car)))),
        )
        assertEquals(
            MarkerRef.Denm("1021000/7"),
            LiveMapFeatures.markerOf(tapped(LiveMapFeatures.denms(listOf(roadworks)))),
        )
        assertEquals(
            MarkerRef.RoadSideUnit("02:42:42:00:00:01"),
            LiveMapFeatures.markerOf(tapped(LiveMapFeatures.roadSideUnits(listOf(trafficLight)))),
        )
    }

    @Test
    fun otherFeaturesAreNoMarkers() {
        val point = Point.fromLngLat(16.0, 48.0)
        val invalidStationId = JsonObject().apply { addProperty(LiveMapFeatures.STATION_ID, "x") }

        assertNull(LiveMapFeatures.markerOf(Feature.fromGeometry(point)))
        assertNull(LiveMapFeatures.markerOf(Feature.fromGeometry(point, invalidStationId)))
    }
}
