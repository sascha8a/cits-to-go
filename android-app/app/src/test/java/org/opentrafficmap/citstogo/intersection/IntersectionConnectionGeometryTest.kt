package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Test

class IntersectionConnectionGeometryTest {
    @Test
    fun straightConnectionFollowsLaneTangents() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 0f,
            endAdjacentX = 110f,
            endAdjacentY = 0f,
            maxControlDistance = 100f,
        )

        assertEquals(38f, controls.startX, 0.001f)
        assertEquals(0f, controls.startY, 0.001f)
        assertEquals(62f, controls.endX, 0.001f)
        assertEquals(0f, controls.endY, 0.001f)
    }

    @Test
    fun turningConnectionPreservesBothApproachDirections() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 100f,
            endAdjacentX = 100f,
            endAdjacentY = 110f,
            maxControlDistance = 200f,
        )

        assertEquals(53.74f, controls.startX, 0.01f)
        assertEquals(0f, controls.startY, 0.001f)
        assertEquals(100f, controls.endX, 0.001f)
        assertEquals(46.26f, controls.endY, 0.01f)
    }

    @Test
    fun tangentPointingAwayFromJunctionFallsBackTowardDestination() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = 10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 0f,
            endAdjacentX = 90f,
            endAdjacentY = 0f,
            maxControlDistance = 100f,
        )

        assertEquals(38f, controls.startX, 0.001f)
        assertEquals(62f, controls.endX, 0.001f)
    }

    @Test
    fun controlDistanceIsClampedForWideGeometryGaps() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 1000f,
            endY = 0f,
            endAdjacentX = 1010f,
            endAdjacentY = 0f,
            maxControlDistance = 80f,
        )

        assertEquals(80f, controls.startX, 0.001f)
        assertEquals(920f, controls.endX, 0.001f)
    }
}
