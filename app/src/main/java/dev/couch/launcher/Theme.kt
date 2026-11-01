package dev.couch.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val Poppins = FontFamily(
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
    Font(R.font.poppins_black, FontWeight.Black),
)

data class CouchTheme(
    val id: String = "midnight-mint",
    val name: String = "Midnight Mint",
    val background: Color = Color(0xFF0B0B14),
    val surface: Color = Color(0xFF1A1640),
    val accent: Color = Color(0xFF6331FB),
    val focus: Color = Color(0xFF88F9C0),
    val text: Color = Color.White,
    val secondary: Color = Color(0xFFC9C9DC),
    val focusWidth: Float = 4f,
    val focusScale: Float = 1.045f,
)
