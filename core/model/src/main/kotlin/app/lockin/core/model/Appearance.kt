package app.lockin.core.model

/** Light, dark, or whatever the phone uses. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * The accent-coloured parts of one theme (light or dark), as ARGB ints so both Compose and the
 * plain-View lock screen can use them. Surfaces and text stay neutral in every accent.
 */
class AccentTones(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    /** Selection containers: tab indicator, segmented buttons, tonal buttons. */
    val selection: Int,
    val onSelection: Int,
    /** Heatmap steps from least to most study time (the empty cell is a neutral surface). */
    val heat: List<Int>,
)

/**
 * Accent colours the user can pick. BLUE is the original Lock In palette. Every other accent is
 * that palette with only the hue changed (same HCT tone and chroma per role), so contrast stays
 * the same: on-primary text is at least 6.5:1 and the primary colour at least 5.6:1 against the
 * surfaces, in both light and dark. The web app (web/src/core/model.ts) uses the same values.
 */
enum class AccentColor(val label: String, val swatch: Int, val light: AccentTones, val dark: AccentTones) {
    BLUE(
        "Blue",
        0xFF3987E5.toInt(),
        light = accentTones(0xFF2F5E9E, 0xFFFFFFFF, 0xFFD5E3FF, 0xFF0B1D36, 0xFFDCE6F5, 0xFF0B1D36, 0xFF9EC5F4, 0xFF6DA7EC, 0xFF3987E5, 0xFF1C5CAB),
        dark = accentTones(0xFF9DC3FF, 0xFF0B1D36, 0xFF1E3554, 0xFFD5E3FF, 0xFF243447, 0xFFD5E3FF, 0xFF184F95, 0xFF256ABF, 0xFF3987E5, 0xFF86B6EF),
    ),
    ORANGE(
        "Orange",
        0xFFD36800.toInt(),
        light = accentTones(0xFF964800, 0xFFFFFFFF, 0xFFFFDBC7, 0xFF331400, 0xFFFFDFCD, 0xFF331400, 0xFFFFB07C, 0xFFF0893D, 0xFFD36800, 0xFF944700),
        dark = accentTones(0xFFFFAF7B, 0xFF331400, 0xFF542907, 0xFFFFDBC7, 0xFF4B2B15, 0xFFFFDBC7, 0xFF813D00, 0xFFA95200, 0xFFD36800, 0xFFFA9C5B),
    ),
    GREEN(
        "Green",
        0xFF419841.toInt(),
        light = accentTones(0xFF306A2E, 0xFFFFFFFF, 0xFFD1E9C9, 0xFF0B220A, 0xFFDCE9D5, 0xFF0B220A, 0xFFA0CE97, 0xFF72B36B, 0xFF419841, 0xFF216A24),
        dark = accentTones(0xFF99CF90, 0xFF0B220A, 0xFF1E3B1C, 0xFFD1E9C9, 0xFF253722, 0xFFD1E9C9, 0xFF1B5C1E, 0xFF2B792D, 0xFF419841, 0xFF89C081),
    ),
    TEAL(
        "Teal",
        0xFF009694.toInt(),
        light = accentTones(0xFF006968, 0xFFFFFFFF, 0xFFC4EAE8, 0xFF002121, 0xFFD5E9E8, 0xFF002121, 0xFF81CFCD, 0xFF23B6B3, 0xFF009694, 0xFF006866),
        dark = accentTones(0xFF71D1CE, 0xFF002121, 0xFF003B3A, 0xFFC4EAE8, 0xFF163837, 0xFFC4EAE8, 0xFF005A58, 0xFF007775, 0xFF009694, 0xFF5DC2C0),
    ),
    PURPLE(
        "Purple",
        0xFF9373E2.toInt(),
        light = accentTones(0xFF67519B, 0xFFFFFFFF, 0xFFE9DDFE, 0xFF201835, 0xFFEBE2F6, 0xFF201835, 0xFFCBB8F5, 0xFFAF96EC, 0xFF9373E2, 0xFF674CA7),
        dark = accentTones(0xFFCCB6FE, 0xFF201835, 0xFF392E53, 0xFFE9DDFE, 0xFF362F47, 0xFFE9DDFE, 0xFF594192, 0xFF7658BB, 0xFF9373E2, 0xFFBDA7F0),
    ),
    PINK(
        "Pink",
        0xFFD15F94.toInt(),
        light = accentTones(0xFF914367, 0xFFFFFFFF, 0xFFFFD9E5, 0xFF311320, 0xFFFCDDE7, 0xFF311320, 0xFFF7ACC9, 0xFFE686AF, 0xFFD15F94, 0xFF973C67),
        dark = accentTones(0xFFFEA8CA, 0xFF311320, 0xFF502739, 0xFFFFD9E5, 0xFF472A36, 0xFFFFD9E5, 0xFF833359, 0xFFAB4776, 0xFFD15F94, 0xFFEF99BC),
    ),
    ;

    fun tonesFor(dark: Boolean): AccentTones = if (dark) this.dark else light
}

private fun accentTones(
    primary: Long,
    onPrimary: Long,
    primaryContainer: Long,
    onPrimaryContainer: Long,
    selection: Long,
    onSelection: Long,
    vararg heat: Long,
) = AccentTones(
    primary = primary.toInt(),
    onPrimary = onPrimary.toInt(),
    primaryContainer = primaryContainer.toInt(),
    onPrimaryContainer = onPrimaryContainer.toInt(),
    selection = selection.toInt(),
    onSelection = onSelection.toInt(),
    heat = heat.map { it.toInt() },
)

/** Whether to draw dark, given the user's choice and whether the phone is in dark mode. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
