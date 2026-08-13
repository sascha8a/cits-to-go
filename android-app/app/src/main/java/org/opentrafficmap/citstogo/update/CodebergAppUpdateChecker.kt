package org.opentrafficmap.citstogo.update

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class AppUpdate(val tag: String, val version: String, val releaseUrl: String)

internal object AppVersionComparator {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val normalized = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (normalized.isBlank()) return null
        return normalized.split('.').map { it.toIntOrNull() ?: return null }
    }
}

internal object CodebergAppUpdateParser {
    fun findNewest(json: String, currentVersion: String): AppUpdate? {
        val releases = JSONArray(json)
        var newest: AppUpdate? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name").trim()
            val version = tag.removePrefix("v")
            if (tag.isBlank() || !AppVersionComparator.isNewer(version, currentVersion)) continue
            val candidate = AppUpdate(tag, version, "${CodebergAppUpdateChecker.REPOSITORY_URL}/releases/tag/$tag")
            if (newest == null || AppVersionComparator.isNewer(candidate.version, newest.version)) newest = candidate
        }
        return newest
    }
}

internal class CodebergAppUpdateChecker {
    fun findUpdate(currentVersion: String): AppUpdate? {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "CITS-to-go-Android")
        try {
            if (connection.responseCode !in 200..299) throw IOException("Codeberg returned HTTP ${connection.responseCode}")
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            return CodebergAppUpdateParser.findNewest(json, currentVersion)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REPOSITORY_URL = "https://codeberg.org/sascha8a/cits-to-go"
        private const val RELEASES_URL = "https://codeberg.org/api/v1/repos/sascha8a/cits-to-go/releases?limit=50"
    }
}
