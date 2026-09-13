package com.xradar.app.feature.drive.component

import android.content.Context
import org.json.JSONObject

/**
 * The Apple-Plans-like basemap. One layer structure ([STYLE_ASSET]) painted with the day
 * or night palette ([PALETTES_ASSET]): every `"@token"` in the style becomes that palette's
 * value. Vector tiles, glyphs and sprites are still Stadia's — only the look is ours.
 */
internal object PlansMapStyle {

    private const val STYLE_ASSET = "map/plans-style.json"
    private const val PALETTES_ASSET = "map/plans-palettes.json"
    private const val KEY_PLACEHOLDER = "{{STADIA_API_KEY}}"

    /** Loud on purpose: a token missing from a palette shows up magenta, never as a blank map. */
    private const val MISSING_TOKEN = "#FF00FF"

    private val TOKEN = Regex("\"@([A-Za-z0-9_]+)\"")

    @Volatile private var template: String? = null
    @Volatile private var palettes: JSONObject? = null

    /** Style JSON for the night ([dark]) or day map, ready for `Style.Builder().fromJson`. */
    fun json(context: Context, dark: Boolean, apiKey: String): String {
        val palette = palettes(context).getJSONObject(if (dark) "dark" else "light")
        return TOKEN.replace(template(context)) { match ->
            JSONObject.quote(palette.optString(match.groupValues[1], MISSING_TOKEN))
        }.replace(KEY_PLACEHOLDER, apiKey)
    }

    private fun template(context: Context): String =
        template ?: readAsset(context, STYLE_ASSET).also { template = it }

    private fun palettes(context: Context): JSONObject =
        palettes ?: JSONObject(readAsset(context, PALETTES_ASSET)).also { palettes = it }

    private fun readAsset(context: Context, name: String): String =
        context.applicationContext.assets.open(name).bufferedReader().use { it.readText() }
}
