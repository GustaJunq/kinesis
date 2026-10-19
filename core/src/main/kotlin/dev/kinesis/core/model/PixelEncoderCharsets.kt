package dev.kinesis.core.model

/**
 * The glyphs of the Pixel Encoder effect, ordered from the lightest (first, a blank) to the darkest. The app draws them once
 * into an atlas texture, one square per level. `blocks` has no characters: the app fills each square from the bottom, so
 * every device draws the same thing without depending on a font.
 */
object PixelEncoderCharsets {
    /** Levels of the `blocks` set (a blank, then eighths of the cell filled from the bottom). */
    const val BLOCK_LEVELS = 9

    private val ramps = mapOf(
        "ascii" to " .:-=+*#%@",
        "numbers" to " 1742359680",
    )

    /** The characters of a set, or null for `blocks` (drawn as shapes). Unknown ids fall back to `ascii`. */
    fun ramp(charset: String): String? = if (charset == "blocks") null else ramps[charset] ?: ramps.getValue("ascii")

    fun levels(charset: String): Int = ramp(charset)?.length ?: BLOCK_LEVELS
}
