package dev.couch.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Google Sans Flex (SIL OFL 1.1), one variable file instanced at the weights the UI uses. */
@OptIn(ExperimentalTextApi::class)
val AppFont = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
    Font(R.font.google_sans_flex, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
})

data class CouchTheme(
    val id: String = "midnight-mint",
    val name: String = "Midnight Mint",
    val background: Color = Color(0xFF0B0B14),
    val surface: Color = Color(0xFF1A1640),
    val accent: Color = Color(0xFF6331FB),
    val focus: Color = Color(0xFF88F9C0),
    val text: Color = Color.White,
    val secondary: Color = Color(0xFFC9C9DC),
    val focusWidth: Float = 3f,
    val focusScale: Float = 1.08f,
    /** Small "In use" badge: the theme's highlight with ink text, else its accent fill with normal text. */
    val badge: Color = accent,
    val onBadge: Color = text,
)

/** Pure colour math also used by JVM tests and asset validation. */
object Contrast {
    fun ratio(a: String, b: String): Double {
        fun luminance(hex: String): Double {
            require(hex.matches(Regex("#[0-9a-fA-F]{6}")))
            val rgb = hex.drop(1).chunked(2).map {
                val channel = it.toInt(16) / 255.0
                if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
            }
            return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}

object ThemeAssets {
    fun validate(json: org.json.JSONObject) {
        for (text in listOf("text", "secondary")) {
            for (background in listOf("background", "surface")) {
                require(Contrast.ratio(json.getString(text), json.getString(background)) >= 4.5)
            }
        }
        for (background in listOf("background", "surface")) {
            require(Contrast.ratio(json.getString("focus"), json.getString(background)) >= 3.0)
        }
        if (json.has("highlight")) require(Contrast.ratio(json.getString("onHighlight"), json.getString("highlight")) >= 4.5)
        else require(Contrast.ratio(json.getString("text"), json.getString("accent")) >= 4.5)
        require(json.getDouble("focusWidth") in 3.0..8.0)
        require(json.getDouble("focusScale") in 1.01..1.08)
    }
    fun parse(json: org.json.JSONObject): CouchTheme {
        validate(json)
        fun color(key: String) = Color((0xFF000000L or json.getString(key).drop(1).toLong(16)).toInt())
        val highlight = json.has("highlight")
        return CouchTheme(json.getString("id"), json.getString("name"), color("background"), color("surface"),
            color("accent"), color("focus"), color("text"), color("secondary"),
            json.getDouble("focusWidth").toFloat(), json.getDouble("focusScale").toFloat(),
            color(if (highlight) "highlight" else "accent"), color(if (highlight) "onHighlight" else "text"))
    }
    fun load(context: android.content.Context): List<CouchTheme> =
        context.assets.list("themes").orEmpty().filter { it.endsWith(".json") }.sorted().map {
            parse(org.json.JSONObject(context.assets.open("themes/$it").bufferedReader().use { reader -> reader.readText() }))
        }
}
