package org.opentrafficmap.citstogo.livemap

/** The DENM icons of the map. Causes without an icon of their own show a general hazard. */
enum class DenmIconGroup {
    TrafficJam,
    Accident,
    Roadworks,
    StationaryVehicle,
    Emergency,
    Hazard;

    val icon: String get() = "denm-${name.lowercase()}"
}

/**
 * English labels for DENM causes and subcauses: every cause and subcause of CDD v1.3.1, and those that CDD v2 added.
 * A subcause without a label of its own, such as 0 (unavailable), takes the label of its cause.
 */
object DenmCauses {
    private class Cause(val label: String, vararg subCauses: Pair<Int, String>) {
        val subCauses = mapOf(*subCauses)
    }

    private val causes = mapOf(
        1 to Cause(
            "Traffic jam",
            1 to "Increased traffic volume",
            2 to "Traffic jam slowly increasing",
            3 to "Traffic jam increasing",
            4 to "Traffic jam strongly increasing",
            5 to "Traffic jam", // trafficStationary in CDD v1.3.1, trafficJam since CDD v2.
            6 to "Traffic jam slightly decreasing",
            7 to "Traffic jam decreasing",
            8 to "Traffic jam strongly decreasing",
            9 to "Traffic jam stable",
        ),
        2 to Cause(
            "Accident",
            1 to "Multi-vehicle accident",
            2 to "Heavy accident",
            3 to "Accident involving lorry",
            4 to "Accident involving bus",
            5 to "Accident involving hazardous materials",
            6 to "Accident on opposite lane",
            7 to "Unsecured accident",
            8 to "Assistance requested",
        ),
        3 to Cause(
            "Roadworks",
            1 to "Major roadworks",
            2 to "Road marking work",
            3 to "Slow moving road maintenance",
            4 to "Short-term stationary roadworks",
            5 to "Street cleaning",
            6 to "Winter service",
        ),
        5 to Cause(
            "Road impassable",
            1 to "Flooding",
            2 to "Danger of avalanches",
            3 to "Blasting of avalanches",
            4 to "Landslips",
            5 to "Chemical spillage",
            6 to "Winter closure",
            7 to "Sinkhole",
            8 to "Earthquake damage",
            9 to "Fallen trees",
            10 to "Rockfalls",
            11 to "Sewer overflow",
            12 to "Storm damage",
            13 to "Subsidence",
            14 to "Burst pipe",
            15 to "Burst water main",
            16 to "Fallen power cables",
            17 to "Snow drifts",
        ),
        6 to Cause(
            "Slippery road",
            1 to "Heavy frost on road",
            2 to "Fuel on road",
            3 to "Mud on road",
            4 to "Snow on road",
            5 to "Ice on road",
            6 to "Black ice on road",
            7 to "Oil on road",
            8 to "Loose chippings",
            9 to "Instant black ice",
            10 to "Roads salted",
        ),
        7 to Cause("Aquaplaning"),
        9 to Cause(
            "Road surface hazard",
            1 to "Rockfalls",
            2 to "Earthquake damage",
            3 to "Sewer collapse",
            4 to "Subsidence",
            5 to "Snow drifts",
            6 to "Storm damage",
            7 to "Burst pipe",
            8 to "Volcano eruption",
            9 to "Falling ice",
            10 to "Fire",
        ),
        10 to Cause(
            "Obstacle on the road",
            1 to "Shed load",
            2 to "Parts of vehicles",
            3 to "Parts of tyres",
            4 to "Big objects",
            5 to "Fallen trees",
            6 to "Hub caps",
            7 to "Waiting vehicles",
        ),
        11 to Cause(
            "Animal on the road",
            1 to "Wild animals",
            2 to "Herd of animals",
            3 to "Small animals",
            4 to "Large animals",
            5 to "Small wild animals",
            6 to "Large wild animals",
            7 to "Domestic animals",
            8 to "Small domestic animals",
            9 to "Large domestic animals",
        ),
        12 to Cause(
            "People on the road",
            1 to "Children on the road",
            2 to "Cyclist on the road",
            3 to "Motorcyclist on the road",
            4 to "Pedestrian on the road",
            5 to "Ordinary pedestrian on the road",
            6 to "Road worker on the road",
            7 to "First responder on the road",
            8 to "Light VRU vehicle on the road",
            9 to "Bicyclist on the road",
            10 to "Wheelchair user on the road",
            11 to "Horse and rider on the road",
            12 to "Roller skater on the road",
            13 to "E-scooter on the road",
            14 to "Personal transporter on the road",
            15 to "Pedelec on the road",
            16 to "Speed pedelec on the road",
            17 to "Powered two-wheeler on the road",
            18 to "Moped on the road",
            19 to "Motorcycle on the road",
            20 to "Motorcycle with right sidecar on the road",
            21 to "Motorcycle with left sidecar on the road",
        ),
        14 to Cause(
            "Wrong-way driver",
            1 to "Wrong-way driver (wrong lane)",
            2 to "Wrong-way driver",
        ),
        15 to Cause(
            "Rescue and recovery work",
            1 to "Emergency vehicles on site",
            2 to "Rescue helicopter landing",
            3 to "Police activity",
            4 to "Medical emergency",
            5 to "Child abduction in progress",
            6 to "Prioritized vehicles on site",
            7 to "Rescue and recovery vehicles on site",
        ),
        17 to Cause(
            "Extreme weather",
            1 to "Strong winds",
            2 to "Damaging hail",
            3 to "Hurricane",
            4 to "Thunderstorm",
            5 to "Tornado",
            6 to "Blizzard",
        ),
        18 to Cause(
            "Reduced visibility",
            1 to "Fog",
            2 to "Smoke",
            3 to "Heavy snowfall",
            4 to "Heavy rain",
            5 to "Heavy hail",
            6 to "Low sun glare",
            7 to "Sandstorms",
            8 to "Swarms of insects",
        ),
        19 to Cause(
            "Precipitation",
            1 to "Heavy rain",
            2 to "Heavy snowfall",
            3 to "Soft hail",
        ),
        20 to Cause("Violence"),
        26 to Cause(
            "Slow vehicle",
            1 to "Maintenance vehicle",
            2 to "Vehicles slowing to look at accident",
            3 to "Abnormal load",
            4 to "Abnormal wide load",
            5 to "Convoy",
            6 to "Snowplough",
            7 to "De-icing vehicle",
            8 to "Salting vehicle",
        ),
        27 to Cause(
            "Dangerous end of queue",
            1 to "Sudden end of queue",
            2 to "Queue over hill",
            3 to "Queue around bend",
            4 to "Queue in tunnel",
        ),
        28 to Cause("Public transport vehicle approaching"),
        91 to Cause(
            "Vehicle breakdown",
            1 to "Lack of fuel",
            2 to "Lack of battery power",
            3 to "Engine problem",
            4 to "Transmission problem",
            5 to "Engine cooling problem",
            6 to "Braking system problem",
            7 to "Steering problem",
            8 to "Tyre puncture",
            9 to "Tyre pressure problem",
            10 to "Vehicle on fire",
        ),
        92 to Cause(
            "Post-crash",
            1 to "Accident without eCall triggered",
            2 to "Accident with eCall manually triggered",
            3 to "Accident with eCall automatically triggered",
            4 to "Accident with eCall triggered without cellular network",
        ),
        93 to Cause(
            "Human problem",
            1 to "Glycemia problem",
            2 to "Heart problem",
        ),
        94 to Cause(
            "Stationary vehicle",
            1 to "Stationary vehicle (human problem)",
            2 to "Broken-down vehicle",
            3 to "Post-crash vehicle",
            4 to "Public transport stop",
            5 to "Vehicle carrying dangerous goods",
            6 to "Vehicle on fire",
        ),
        95 to Cause(
            "Emergency vehicle approaching",
            1 to "Emergency vehicle approaching",
            2 to "Prioritized vehicle approaching",
        ),
        96 to Cause(
            "Dangerous curve",
            1 to "Dangerous left curve",
            2 to "Dangerous right curve",
            3 to "Multiple curves starting in unknown direction",
            4 to "Multiple curves starting to the left",
            5 to "Multiple curves starting to the right",
        ),
        97 to Cause(
            "Collision risk",
            1 to "Longitudinal collision risk",
            2 to "Crossing collision risk",
            3 to "Lateral collision risk",
            4 to "Vulnerable road user",
            5 to "Collision risk with pedestrian",
            6 to "Collision risk with cyclist",
            7 to "Collision risk with motor vehicle",
        ),
        98 to Cause(
            "Signal violation",
            1 to "Stop sign violation",
            2 to "Traffic light violation",
            3 to "Turning regulation violation",
        ),
        99 to Cause(
            "Dangerous situation",
            1 to "Emergency electronic brake lights",
            2 to "Pre-crash system engaged",
            3 to "ESP engaged",
            4 to "ABS engaged",
            5 to "AEB engaged", // aebEngaged in CDD v1.3.1, ebEngaged since CDD v2.
            6 to "Brake warning engaged",
            7 to "Collision risk warning engaged",
        ),
        100 to Cause(
            "Railway level crossing",
            1 to "Do not cross, abnormal situation",
            2 to "Level crossing closed",
            3 to "Unguarded level crossing",
            4 to "Level crossing in normal operation",
        ),
    )

    fun label(causeCode: Int, subCauseCode: Int): String {
        val cause = causes[causeCode] ?: return "Cause $causeCode / sub $subCauseCode"
        return cause.subCauses[subCauseCode] ?: cause.label
    }

    fun causeLabel(causeCode: Int): String? = causes[causeCode]?.label

    /** `null` for subcauses without a label of their own. */
    fun subCauseLabel(causeCode: Int, subCauseCode: Int): String? = causes[causeCode]?.subCauses?.get(subCauseCode)

    fun iconGroup(causeCode: Int): DenmIconGroup = when (causeCode) {
        1, 27 -> DenmIconGroup.TrafficJam
        2 -> DenmIconGroup.Accident
        3 -> DenmIconGroup.Roadworks
        in 91..94 -> DenmIconGroup.StationaryVehicle
        15, 95 -> DenmIconGroup.Emergency
        else -> DenmIconGroup.Hazard
    }
}
