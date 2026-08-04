package org.opentrafficmap.citstogo.flashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodebergReleaseParserTest {
    @Test
    fun selectsExactVersionAndFirmwareArtifact() {
        val release = CodebergReleaseParser.findForVersion(RELEASES, "2.17.0")

        assertEquals("v2.17.0", release?.tag)
        assertEquals("CITS-to-go-firmware-v2.17.0.bin", release?.firmwareName)
        assertEquals(672288L, release?.firmwareSize)
    }

    @Test
    fun doesNotUseAnotherVersionOrDraft() {
        assertNull(CodebergReleaseParser.findForVersion(RELEASES, "2.16.0"))
    }

    @Test
    fun readsChecksumOnlyForNamedFirmware() {
        val expected = "a".repeat(64)
        val manifest = "${"b".repeat(64)}  another.bin\n$expected  CITS-to-go-firmware-v2.17.0.bin\n"

        assertEquals(expected, CodebergReleaseParser.expectedSha256(manifest, "CITS-to-go-firmware-v2.17.0.bin"))
    }

    private companion object {
        val RELEASES = """
            [
              {"tag_name":"v2.16.0","draft":false,"prerelease":false,"assets":[]},
              {"tag_name":"v2.17.0","draft":true,"prerelease":false,"assets":[]},
              {"tag_name":"v2.17.0","draft":false,"prerelease":false,"assets":[
                {"name":"CITS-to-go-firmware-v2.17.0.bin","size":672288,"browser_download_url":"https://codeberg.org/sascha8a/cits-to-go/releases/download/v2.17.0/CITS-to-go-firmware-v2.17.0.bin"},
                {"name":"SHA256sum.txt","size":187,"browser_download_url":"https://codeberg.org/sascha8a/cits-to-go/releases/download/v2.17.0/SHA256sum.txt"}
              ]}
            ]
        """.trimIndent()
    }
}
