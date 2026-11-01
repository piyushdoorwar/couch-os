package dev.couch.launcher

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TileWidth = 224.dp
private val TileHeight = 126.dp
private val TileShape = RoundedCornerShape(16.dp)

@Composable
fun LauncherScreen(model: LauncherModel, actions: SystemActions) {
    val apps by model.apps.collectAsStateWithLifecycle()
    val config by model.config.collectAsStateWithLifecycle()
    val themes by model.themes.collectAsStateWithLifecycle()
    val themeId by model.themeId.collectAsStateWithLifecycle()
    val theme = themes.find { it.id == themeId } ?: CouchTheme()
    val homeRequest by model.homeRequest.collectAsStateWithLifecycle()
    val speedEnabled by model.speedEnabled.collectAsStateWithLifecycle()
    val monochrome by model.monochrome.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(homeRequest) { if (homeRequest > 0) page = "home" }
    val lifecycleOwner = LocalLifecycleOwner.current
    val temperature by produceState<String?>(null, config, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastCheck = 0L
            while (true) {
                if (!model.weather.online()) { value = null; lastCheck = 0L }
                else if (lastCheck == 0L || System.currentTimeMillis() - lastCheck >= WeatherCache.REFRESH_MS) {
                    value = model.weather.temperature(config)
                    lastCheck = System.currentTimeMillis()
                }
                delay(5_000L)
            }
        }
    }
    // Opt-in. Reads kernel byte counters while HOME is visible; generates no traffic itself.
    val speed by produceState<String?>(null, speedEnabled, lifecycleOwner) {
        if (!speedEnabled) { value = null; return@produceState }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.speed.reset()
            while (true) {
                model.speed.sample()?.let { value = it }
                delay(LiveSpeed.SAMPLE_MS)
            }
        }
    }
    val favourites = remember(apps, config.favourites) { favouriteApps(apps, config.favourites) }
    // Refresh when returning from an app or system settings. No package inventory telemetry.
    LifecycleStartEffect(Unit) { model.refresh(); onStopOrDispose {} }
    BackHandler { page = "home" }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (page) {
                "apps" -> AppGrid(apps.filter { it !in favourites }, theme, monochrome, actions) { page = "home" }
                "settings" -> LauncherSettings(themes, theme, model::selectTheme, speedEnabled, model::setSpeedEnabled,
                    monochrome, model::setMonochrome, actions) { page = "home" }
                else -> Home(favourites, theme, monochrome, config, actions, temperature, speed, { page = "apps" }, { page = "settings" })
            }
        }
    }
}

@Composable
private fun Home(favourites: List<TvApp>, theme: CouchTheme, monochrome: Boolean, config: LocalConfig, actions: SystemActions,
                 temperature: String?, speed: String?, allApps: () -> Unit, settings: () -> Unit) {
    val firstFocus = initialFocusModifier(favourites.map { it.packageName })
    Column(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(R.drawable.ic_input, "Inputs", theme) { actions.inputs(config) }
                IconButton(R.drawable.ic_wifi, "Wi-Fi", theme) { actions.wifi() }
                IconButton(R.drawable.ic_account_circle, "Google account", theme) { actions.account() }
                IconButton(R.drawable.ic_settings, "Settings", theme, onClick = settings)
            }
        }
        Spacer(Modifier.weight(1f))
        ClockHero(theme, temperature, speed)
        Spacer(Modifier.height(36.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            items(favourites, key = { it.packageName }) { app ->
                AppTile(app, theme, monochrome, if (app == favourites.first()) firstFocus else Modifier) { actions.launch(app) }
            }
            item(key = "all-apps") {
                GlyphTile(R.drawable.ic_apps, "All apps", theme, if (favourites.isEmpty()) firstFocus else Modifier, allApps)
            }
        }
    }
}

@Composable
fun ClockHero(theme: CouchTheme, temperature: String? = null, speed: String? = null) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = ZonedDateTime.now()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    Column(Modifier.padding(horizontal = 48.dp)) {
        Text(now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)), color = theme.text,
            fontFamily = Poppins, fontWeight = FontWeight.Black, fontSize = 80.sp, lineHeight = 92.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parts = listOfNotNull(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), temperature)
            Text(parts.joinToString("   ·   "), color = theme.secondary, fontFamily = Poppins,
                fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            if (speed != null) {
                Text("   ·   ", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Image(painterResource(R.drawable.ic_arrow_downward), contentDescription = "Download",
                    colorFilter = ColorFilter.tint(theme.secondary), modifier = Modifier.size(22.dp))
                Text(" $speed", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun AppGrid(apps: List<TvApp>, theme: CouchTheme, monochrome: Boolean, actions: SystemActions, back: () -> Unit) {
    val firstFocus = initialFocusModifier(apps.map { it.packageName })
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Heading("All apps", theme)
        if (apps.isEmpty()) {
            Text("No other TV apps installed", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 32.dp))
            Spacer(Modifier.height(24.dp))
            RowButton("Back", theme = theme, modifier = firstFocus, onClick = back)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(TileWidth), contentPadding = PaddingValues(vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(apps, key = { it.packageName }) { app ->
                AppTile(app, theme, monochrome, if (app == apps.first()) firstFocus else Modifier) { actions.launch(app) }
            }
        }
    }
}

@Composable
fun Heading(label: String, theme: CouchTheme) = Text(label, color = theme.text, fontFamily = Poppins,
    fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)

/**
 * Grey version of the app's own artwork, slightly dimmed until focused. Light artwork is inverted so
 * every tile reads as a light logo on a dark plate. Nothing is bundled; this only filters what the TV supplies.
 */
private fun monochromeFilter(focused: Boolean, invert: Boolean): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(0f) }
    if (invert) matrix.timesAssign(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    val level = if (focused) 1f else 0.82f
    matrix.timesAssign(ColorMatrix(floatArrayOf(
        level, 0f, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    return ColorFilter.colorMatrix(matrix)
}

@Composable
private fun AppTile(app: TvApp, theme: CouchTheme, monochrome: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    // Tiles render at once; each banner decodes off the main thread and is cached across visits.
    val artwork by produceState(ArtworkCache.peek(app), app) {
        if (value == null) value = withContext(Dispatchers.IO) { ArtworkCache.load(context, app) }
    }
    val bitmap = artwork?.image
    val light = artwork?.light == true
    TileWithCaption(app.label, theme, modifier, onClick) { focused ->
        when {
            bitmap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(app.label.take(1), color = theme.text, fontSize = 40.sp, fontFamily = Poppins, fontWeight = FontWeight.ExtraBold)
            }
            // Banners are designed edge to edge; square icons sit centred on the surface.
            artwork?.wide == true -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = if (monochrome) monochromeFilter(focused, light) else null)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(bitmap, null, Modifier.size(80.dp), contentScale = ContentScale.Fit,
                    colorFilter = if (monochrome) monochromeFilter(focused, false) else null)
            }
        }
    }
}

@Composable
private fun GlyphTile(@DrawableRes icon: Int, label: String, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TileWithCaption(label, theme, modifier, onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(theme.text))
        }
    }
}

/** tvOS-style tile: artwork only; the name appears beneath it while focused. */
@Composable
private fun TileWithCaption(label: String, theme: CouchTheme, modifier: Modifier, onClick: () -> Unit,
                            content: @Composable BoxScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val captionAlpha by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 600f), label = "caption")
    Column(modifier.width(TileWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Focusable(theme, TileShape, onClick, Modifier.size(TileWidth, TileHeight).semantics { contentDescription = label },
            onFocus = { focused = it }) { content(focused) }
        Text(label, color = theme.text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 18.dp).graphicsLayer { alpha = captionAlpha })
    }
}

@Composable
private fun IconButton(@DrawableRes icon: Int, label: String, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(theme, CircleShape, onClick, modifier.size(56.dp).semantics { contentDescription = label }) {
        Image(painterResource(icon), null, Modifier.align(Alignment.Center).size(26.dp), colorFilter = ColorFilter.tint(theme.text))
    }
}

/** One focus treatment everywhere: spring scale, focus-colour ring and a soft glow, drawn only while focused. */
@Composable
fun Focusable(theme: CouchTheme, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier,
              onFocus: (Boolean) -> Unit = {}, focusScale: Float = theme.focusScale, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f,
        spring(dampingRatio = 0.85f, stiffness = 500f), label = "focus")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind {
            if (focused) drawRoundRect(Brush.radialGradient(listOf(theme.focus.copy(alpha = 0.18f), Color.Transparent),
                center = center, radius = size.maxDimension * 0.7f),
                topLeft = Offset(-16.dp.toPx(), -16.dp.toPx()),
                size = Size(size.width + 32.dp.toPx(), size.height + 32.dp.toPx()),
                cornerRadius = CornerRadius(28.dp.toPx()))
        }
        .clip(shape).background(theme.surface)
        .border(if (focused) theme.focusWidth.dp else 0.dp, if (focused) theme.focus else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick), content = content)
}

/** Settings row: label on the left, current value on the right, like tvOS. */
@Composable
private fun RowButton(label: String, value: String? = null, theme: CouchTheme, modifier: Modifier = Modifier,
                      badge: String? = null, width: Dp = 760.dp, onClick: () -> Unit) {
    // Wide rows only lift slightly; a full tile-size zoom would crowd the list.
    Focusable(theme, RoundedCornerShape(14.dp), onClick, modifier.width(width), focusScale = 1.02f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = theme.text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                modifier = Modifier.weight(1f))
            // Badge sits inside the surface row so the focus ring keeps its validated contrast.
            if (badge != null) Text(badge, color = theme.onBadge, fontFamily = Poppins,
                fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(theme.badge)
                    .padding(horizontal = 10.dp, vertical = 3.dp))
            if (value != null) Text(value, color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SectionLabel(label: String, theme: CouchTheme) = Text(label, color = theme.secondary, fontFamily = Poppins,
    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp))

@Composable
private fun LauncherSettings(themes: List<CouchTheme>, theme: CouchTheme, choose: (String) -> Unit,
                             speedEnabled: Boolean, setSpeed: (Boolean) -> Unit,
                             monochrome: Boolean, setMonochrome: (Boolean) -> Unit,
                             actions: SystemActions, back: () -> Unit) {
    val firstFocus = initialFocusModifier(themes.map { it.id })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("Settings", theme) }
        item { SectionLabel("THEME", theme) }
        items(themes, key = { it.id }) { option ->
            RowButton(option.name, theme = theme, badge = if (option.id == theme.id) "IN USE" else null,
                modifier = if (option == themes.first()) firstFocus else Modifier) { choose(option.id) }
        }
        item { SectionLabel("HOME SCREEN", theme) }
        item { RowButton("App artwork", if (monochrome) "Monochrome" else "Colour", theme) { setMonochrome(!monochrome) } }
        item { RowButton("Live download speed", if (speedEnabled) "On" else "Off", theme) { setSpeed(!speedEnabled) } }
        item { SectionLabel("TV", theme) }
        item { RowButton("Google account", null, theme) { actions.account() } }
        item { RowButton("TV settings", null, theme) { actions.settings() } }
        item {
            Text("Live speed shows how fast this TV is downloading right now. It adds no traffic. Weather by Open-Meteo · CC BY 4.0",
                color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
        item { RowButton("Back", null, theme, onClick = back) }
    }
}


/** Lazy children attach after the parent's composition effects; wait for placement. */
@Composable
private fun initialFocusModifier(key: Any = Unit): Modifier {
    val requester = remember(key) { FocusRequester() }
    var requested by remember(key) { mutableStateOf(false) }
    return Modifier.focusRequester(requester).onGloballyPositioned {
        if (!requested) {
            requester.requestFocus()
            requested = true
        }
    }
}
