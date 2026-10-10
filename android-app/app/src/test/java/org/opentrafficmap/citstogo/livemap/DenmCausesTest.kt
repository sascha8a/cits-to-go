package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

class DenmCausesTest {
    /**
     * Labels of every CDD v1.3.1 cause and named subcause as `cause|subcause|label`, with `*` for the cause itself.
     * Subcause 0 (unavailable) and the unnamed ones show the label of their cause.
     */
    private val cddV131Labels = """
        1|*|Traffic jam
        1|1|Increased traffic volume
        1|2|Traffic jam slowly increasing
        1|3|Traffic jam increasing
        1|4|Traffic jam strongly increasing
        1|5|Traffic jam
        1|6|Traffic jam slightly decreasing
        1|7|Traffic jam decreasing
        1|8|Traffic jam strongly decreasing
        2|*|Accident
        2|1|Multi-vehicle accident
        2|2|Heavy accident
        2|3|Accident involving lorry
        2|4|Accident involving bus
        2|5|Accident involving hazardous materials
        2|6|Accident on opposite lane
        2|7|Unsecured accident
        2|8|Assistance requested
        3|*|Roadworks
        3|1|Major roadworks
        3|2|Road marking work
        3|3|Slow moving road maintenance
        3|4|Short-term stationary roadworks
        3|5|Street cleaning
        3|6|Winter service
        5|*|Road impassable
        6|*|Slippery road
        6|1|Heavy frost on road
        6|2|Fuel on road
        6|3|Mud on road
        6|4|Snow on road
        6|5|Ice on road
        6|6|Black ice on road
        6|7|Oil on road
        6|8|Loose chippings
        6|9|Instant black ice
        6|10|Roads salted
        7|*|Aquaplaning
        9|*|Road surface hazard
        9|1|Rockfalls
        9|2|Earthquake damage
        9|3|Sewer collapse
        9|4|Subsidence
        9|5|Snow drifts
        9|6|Storm damage
        9|7|Burst pipe
        9|8|Volcano eruption
        9|9|Falling ice
        10|*|Obstacle on the road
        10|1|Shed load
        10|2|Parts of vehicles
        10|3|Parts of tyres
        10|4|Big objects
        10|5|Fallen trees
        10|6|Hub caps
        10|7|Waiting vehicles
        11|*|Animal on the road
        11|1|Wild animals
        11|2|Herd of animals
        11|3|Small animals
        11|4|Large animals
        12|*|People on the road
        12|1|Children on the road
        12|2|Cyclist on the road
        12|3|Motorcyclist on the road
        14|*|Wrong-way driver
        14|1|Wrong-way driver (wrong lane)
        14|2|Wrong-way driver
        15|*|Rescue and recovery work
        15|1|Emergency vehicles on site
        15|2|Rescue helicopter landing
        15|3|Police activity
        15|4|Medical emergency
        15|5|Child abduction in progress
        17|*|Extreme weather
        17|1|Strong winds
        17|2|Damaging hail
        17|3|Hurricane
        17|4|Thunderstorm
        17|5|Tornado
        17|6|Blizzard
        18|*|Reduced visibility
        18|1|Fog
        18|2|Smoke
        18|3|Heavy snowfall
        18|4|Heavy rain
        18|5|Heavy hail
        18|6|Low sun glare
        18|7|Sandstorms
        18|8|Swarms of insects
        19|*|Precipitation
        19|1|Heavy rain
        19|2|Heavy snowfall
        19|3|Soft hail
        26|*|Slow vehicle
        26|1|Maintenance vehicle
        26|2|Vehicles slowing to look at accident
        26|3|Abnormal load
        26|4|Abnormal wide load
        26|5|Convoy
        26|6|Snowplough
        26|7|De-icing vehicle
        26|8|Salting vehicle
        27|*|Dangerous end of queue
        27|1|Sudden end of queue
        27|2|Queue over hill
        27|3|Queue around bend
        27|4|Queue in tunnel
        91|*|Vehicle breakdown
        91|1|Lack of fuel
        91|2|Lack of battery power
        91|3|Engine problem
        91|4|Transmission problem
        91|5|Engine cooling problem
        91|6|Braking system problem
        91|7|Steering problem
        91|8|Tyre puncture
        91|9|Tyre pressure problem
        92|*|Post-crash
        92|1|Accident without eCall triggered
        92|2|Accident with eCall manually triggered
        92|3|Accident with eCall automatically triggered
        92|4|Accident with eCall triggered without cellular network
        93|*|Human problem
        93|1|Glycemia problem
        93|2|Heart problem
        94|*|Stationary vehicle
        94|1|Stationary vehicle (human problem)
        94|2|Broken-down vehicle
        94|3|Post-crash vehicle
        94|4|Public transport stop
        94|5|Vehicle carrying dangerous goods
        95|*|Emergency vehicle approaching
        95|1|Emergency vehicle approaching
        95|2|Prioritized vehicle approaching
        96|*|Dangerous curve
        96|1|Dangerous left curve
        96|2|Dangerous right curve
        96|3|Multiple curves starting in unknown direction
        96|4|Multiple curves starting to the left
        96|5|Multiple curves starting to the right
        97|*|Collision risk
        97|1|Longitudinal collision risk
        97|2|Crossing collision risk
        97|3|Lateral collision risk
        97|4|Vulnerable road user
        98|*|Signal violation
        98|1|Stop sign violation
        98|2|Traffic light violation
        98|3|Turning regulation violation
        99|*|Dangerous situation
        99|1|Emergency electronic brake lights
        99|2|Pre-crash system engaged
        99|3|ESP engaged
        99|4|ABS engaged
        99|5|AEB engaged
        99|6|Brake warning engaged
        99|7|Collision risk warning engaged
    """.trimIndent().lines().map { it.split('|') }

    /** Labels of the causes and named subcauses that CDD v2 added, in the same form. */
    private val cddV2Labels = """
        1|9|Traffic jam stable
        5|1|Flooding
        5|2|Danger of avalanches
        5|3|Blasting of avalanches
        5|4|Landslips
        5|5|Chemical spillage
        5|6|Winter closure
        5|7|Sinkhole
        5|8|Earthquake damage
        5|9|Fallen trees
        5|10|Rockfalls
        5|11|Sewer overflow
        5|12|Storm damage
        5|13|Subsidence
        5|14|Burst pipe
        5|15|Burst water main
        5|16|Fallen power cables
        5|17|Snow drifts
        9|10|Fire
        11|5|Small wild animals
        11|6|Large wild animals
        11|7|Domestic animals
        11|8|Small domestic animals
        11|9|Large domestic animals
        12|4|Pedestrian on the road
        12|5|Ordinary pedestrian on the road
        12|6|Road worker on the road
        12|7|First responder on the road
        12|8|Light VRU vehicle on the road
        12|9|Bicyclist on the road
        12|10|Wheelchair user on the road
        12|11|Horse and rider on the road
        12|12|Roller skater on the road
        12|13|E-scooter on the road
        12|14|Personal transporter on the road
        12|15|Pedelec on the road
        12|16|Speed pedelec on the road
        12|17|Powered two-wheeler on the road
        12|18|Moped on the road
        12|19|Motorcycle on the road
        12|20|Motorcycle with right sidecar on the road
        12|21|Motorcycle with left sidecar on the road
        15|6|Prioritized vehicles on site
        15|7|Rescue and recovery vehicles on site
        20|*|Violence
        28|*|Public transport vehicle approaching
        91|10|Vehicle on fire
        94|6|Vehicle on fire
        97|5|Collision risk with pedestrian
        97|6|Collision risk with cyclist
        97|7|Collision risk with motor vehicle
        100|*|Railway level crossing
        100|1|Do not cross, abnormal situation
        100|2|Level crossing closed
        100|3|Unguarded level crossing
        100|4|Level crossing in normal operation
    """.trimIndent().lines().map { it.split('|') }

    private val cddLabels = cddV131Labels + cddV2Labels

    @Test
    fun everyCddCodeHasItsLabel() {
        assertEquals(158, cddV131Labels.size)
        assertEquals(56, cddV2Labels.size)
        assertEquals(cddLabels.size, cddLabels.map { it[0] to it[1] }.toSet().size)

        for ((cause, rows) in cddLabels.groupBy { it[0].toInt() }) {
            val labels = rows.associate { it[1] to it[2] }
            for (subCause in 0..255) {
                val expected = labels["$subCause"] ?: labels.getValue("*")
                assertEquals("$cause/$subCause", expected, DenmCauses.label(cause, subCause))
            }
        }
    }

    @Test
    fun causeAndSubCauseLabelsAreSeparate() {
        for ((cause, rows) in cddLabels.groupBy { it[0].toInt() }) {
            val labels = rows.associate { it[1] to it[2] }
            assertEquals("$cause", labels.getValue("*"), DenmCauses.causeLabel(cause))
            for (subCause in 0..255) {
                assertEquals("$cause/$subCause", labels["$subCause"], DenmCauses.subCauseLabel(cause, subCause))
            }
        }
        assertEquals(null, DenmCauses.causeLabel(42))
        assertEquals(null, DenmCauses.subCauseLabel(42, 1))
    }

    @Test
    fun unknownCausesShowTheirCodes() {
        val known = cddLabels.map { it[0].toInt() }.toSet()

        for (cause in (0..255) - known) assertEquals("Cause $cause / sub 7", DenmCauses.label(cause, 7))
    }

    @Test
    fun iconGroupsFollowTheCause() {
        val groups = mapOf(
            1 to DenmIconGroup.TrafficJam,
            27 to DenmIconGroup.TrafficJam,
            2 to DenmIconGroup.Accident,
            3 to DenmIconGroup.Roadworks,
            91 to DenmIconGroup.StationaryVehicle,
            92 to DenmIconGroup.StationaryVehicle,
            93 to DenmIconGroup.StationaryVehicle,
            94 to DenmIconGroup.StationaryVehicle,
            15 to DenmIconGroup.Emergency,
            95 to DenmIconGroup.Emergency,
        )

        for (cause in 0..255) {
            assertEquals("$cause", groups[cause] ?: DenmIconGroup.Hazard, DenmCauses.iconGroup(cause))
        }
        assertEquals(DenmIconGroup.entries.size, DenmIconGroup.entries.map { it.icon }.toSet().size)
    }
}
