package org.opentrafficmap.citstogo.flashing

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class FirmwareRelease(
    val tag: String,
    val firmwareName: String,
    val firmwareUrl: String,
    val firmwareSize: Long,
    val checksumsUrl: String,
)

object CodebergReleaseParser {
    fun findForVersion(json: String, version: String): FirmwareRelease? {
        val expectedFirmware = "CITS-to-go-firmware-v$version.bin"
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            if (tag.removePrefix("v") != version) continue
            val assets = release.optJSONArray("assets") ?: continue
            var firmwareName: String? = null
            var firmwareUrl: String? = null
            var firmwareSize = -1L
            var checksumsUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                when (name) {
                    expectedFirmware -> {
                        firmwareName = name
                        firmwareUrl = url
                        firmwareSize = asset.optLong("size", -1L)
                    }
                    "SHA256sum.txt" -> checksumsUrl = url
                }
            }
            if (firmwareName != null && firmwareUrl != null && checksumsUrl != null &&
                isTrustedCodebergDownload(firmwareUrl) && isTrustedCodebergDownload(checksumsUrl)
            ) {
                return FirmwareRelease(tag, firmwareName, firmwareUrl, firmwareSize, checksumsUrl)
            }
        }
        return null
    }

    fun expectedSha256(manifest: String, firmwareName: String): String? =
        manifest.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val name = parts.getOrNull(1)?.removePrefix("*")
            parts.getOrNull(0)?.lowercase()?.takeIf {
                name == firmwareName && it.matches(Regex("[0-9a-f]{64}"))
            }
        }

    private fun isTrustedCodebergDownload(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "codeberg.org"
    }.getOrDefault(false)
}

class CodebergReleaseClient {
    fun findFirmware(version: String): FirmwareRelease {
        val json = download(
            RELEASES_URL,
            MAX_RELEASE_JSON_BYTES,
        ).toString(StandardCharsets.UTF_8)
        return CodebergReleaseParser.findForVersion(json, version)
            ?: throw IOException("No complete firmware release found for app version $version")
    }

    fun downloadAndVerify(
        release: FirmwareRelease,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        if (release.firmwareSize !in 1..MAX_FIRMWARE_BYTES) {
            throw IOException("Firmware artifact has an invalid size")
        }
        val manifest = download(release.checksumsUrl, MAX_CHECKSUM_BYTES)
            .toString(StandardCharsets.UTF_8)
        val expected = CodebergReleaseParser.expectedSha256(manifest, release.firmwareName)
            ?: throw IOException("SHA256sum.txt does not contain ${release.firmwareName}")
        val firmware = download(release.firmwareUrl, MAX_FIRMWARE_BYTES, onProgress)
        if (firmware.size.toLong() != release.firmwareSize) {
            throw IOException("Firmware download size does not match the release")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(firmware).toHex()
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException("Firmware SHA-256 verification failed")
        }
        return firmware
    }

    private fun download(
        url: String,
        maximumBytes: Long,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        requireTrustedUrl(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json, application/octet-stream, text/plain")
        connection.setRequestProperty("User-Agent", "CITS-to-go-Android")
        try {
            val status = connection.responseCode
            if (status in 300..399) {
                val redirect = connection.getHeaderField("Location") ?: throw IOException("Invalid download redirect")
                return download(resolveRedirect(url, redirect), maximumBytes, onProgress)
            }
            if (status !in 200..299) throw IOException("Codeberg returned HTTP $status")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maximumBytes) throw IOException("Download is too large")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maximumBytes) throw IOException("Download is too large")
                    output.write(buffer, 0, read)
                    if (declaredLength > 0) onProgress((total.toFloat() / declaredLength).coerceIn(0f, 1f))
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveRedirect(source: String, redirect: String): String =
        URI(source).resolve(redirect).toString().also(::requireTrustedUrl)

    private fun requireTrustedUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "codeberg.org") {
            throw IOException("Refusing a download outside codeberg.org")
        }
    }

    companion object {
        const val RELEASES_URL = "https://codeberg.org/api/v1/repos/sascha8a/cits-to-go/releases?limit=50"
        const val MAX_FIRMWARE_BYTES = 16L * 1024L * 1024L
        private const val MAX_RELEASE_JSON_BYTES = 2L * 1024L * 1024L
        private const val MAX_CHECKSUM_BYTES = 64L * 1024L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
