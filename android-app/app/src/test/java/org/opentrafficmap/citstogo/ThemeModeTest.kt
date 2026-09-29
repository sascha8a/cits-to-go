package org.opentrafficmap.citstogo

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `theme mode round trips through its preference name`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromPreference(mode.name))
        }
    }

    @Test
    fun `missing or unknown preferences follow the device theme`() {
        assertEquals(ThemeMode.System, ThemeMode.fromPreference(null))
        assertEquals(ThemeMode.System, ThemeMode.fromPreference(""))
        assertEquals(ThemeMode.System, ThemeMode.fromPreference("sepia"))
    }

    @Test
    fun `forced modes ignore the device theme`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.Light, systemInDarkTheme = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.Light, systemInDarkTheme = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.Dark, systemInDarkTheme = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.Dark, systemInDarkTheme = false))
    }

    @Test
    fun `system mode mirrors the device theme`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.System, systemInDarkTheme = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.System, systemInDarkTheme = false))
    }
}
