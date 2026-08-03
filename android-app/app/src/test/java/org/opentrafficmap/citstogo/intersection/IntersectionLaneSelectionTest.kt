package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Test

class IntersectionLaneSelectionTest {
    @Test
    fun allLanesHaveFullOpacityBeforeSelection() {
        assertEquals(1f, intersectionLaneSelectionAlpha(10, emptyList(), emptySet()))
    }

    @Test
    fun selectedAndSelectableLanesKeepFullOpacity() {
        val selectedLaneIds = listOf(10)
        val selectableLaneIds = setOf(11)

        assertEquals(1f, intersectionLaneSelectionAlpha(10, selectedLaneIds, selectableLaneIds))
        assertEquals(1f, intersectionLaneSelectionAlpha(11, selectedLaneIds, selectableLaneIds))
    }

    @Test
    fun everyOtherLaneIsDimmedAfterSelection() {
        assertEquals(0.2f, intersectionLaneSelectionAlpha(99, listOf(10), setOf(11)))
    }

    @Test
    fun connectionsFromSelectedLanesKeepFullOpacity() {
        assertEquals(1f, intersectionConnectionSelectionAlpha(10, 11, listOf(10)))
        assertEquals(1f, intersectionConnectionSelectionAlpha(10, 11, listOf(11)))
    }

    @Test
    fun connectionsNotFromSelectedLanesAreDimmed() {
        assertEquals(0.2f, intersectionConnectionSelectionAlpha(20, 21, listOf(10)))
    }
}
