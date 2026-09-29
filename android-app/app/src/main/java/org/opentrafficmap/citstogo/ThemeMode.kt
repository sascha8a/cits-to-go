package org.opentrafficmap.citstogo

/**
 * Colour theme chosen by the user. The selection is stored as the enum name in the shared
 * preferences; unknown or missing values fall back to [System] so the app follows the device
 * theme by default.
 */
enum class ThemeMode(val label: String) {
    Light("Always light"),
    Dark("Always dark"),
    System("Follow device theme"),
    ;

    companion object {
        fun fromPreference(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: System
    }
}

/** Whether the app should render the dark palette for [mode] on a device in [systemInDarkTheme]. */
fun resolveDarkTheme(mode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemInDarkTheme
}
