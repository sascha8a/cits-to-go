package org.opentrafficmap.citstogo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodebergAppUpdateCheckerTest {
    @Test fun comparesSemanticVersions() {
        assertTrue(AppVersionComparator.isNewer("2.26.0", "2.25.9"))
        assertTrue(AppVersionComparator.isNewer("v3.0", "2.99.99"))
        assertFalse(AppVersionComparator.isNewer("2.25.0", "2.25.0"))
        assertFalse(AppVersionComparator.isNewer("2.24.9", "2.25.0"))
    }

    @Test fun choosesNewestStableRelease() {
        val json = """[
          {"tag_name":"v2.26.0","draft":false,"prerelease":false},
          {"tag_name":"v2.28.0-beta","draft":false,"prerelease":true},
          {"tag_name":"v2.27.1","draft":false,"prerelease":false}
        ]"""
        val update = CodebergAppUpdateParser.findNewest(json, "2.25.0")
        assertEquals("v2.27.1", update?.tag)
        assertEquals("2.27.1", update?.version)
    }

    @Test fun returnsNullWhenCurrentIsLatest() {
        val json = """[{"tag_name":"v2.25.0","draft":false,"prerelease":false}]"""
        assertNull(CodebergAppUpdateParser.findNewest(json, "2.25.0"))
    }
}
