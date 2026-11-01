package dev.couch.launcher

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Proportions follow the Google TV home: 58 dp side margin, five 16:9 tiles per row.
private val Margin = 58.dp
private val TileShape = RoundedCornerShape(8.dp)
private const val Columns = 5

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
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val recentsEnabled by model.recentsEnabled.collectAsStateWithLifecycle()
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
    // Favourites keep their configured order; every other TV app follows alphabetically.
    val ordered = remember(apps, config.favourites) {
        val favourites = favouriteApps(apps, config.favourites)
        favourites + apps.filter { it !in favourites }
    }
    val jumpBackIn = remember(apps, recent, recentsEnabled) {
        if (!recentsEnabled) emptyList() else {
            val byPackage = apps.associateBy { it.packageName }
            recent.mapNotNull { (pkg, at) -> byPackage[pkg]?.let { it to at } }
        }
    }
    val launch: (TvApp) -> Unit = { model.launched(it); actions.launch(it) }
    BackHandler { page = "home" }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (page) {
                "settings" -> LauncherSettings(themes, theme, model::selectTheme, speedEnabled, model::setSpeedEnabled,
                    monochrome, model::setMonochrome, recentsEnabled, model::setRecentsEnabled, config, actions) { page = "home" }
                else -> Home(ordered, loaded, jumpBackIn, theme, monochrome, config, actions, launch, temperature,
                    model, speedEnabled) { page = "settings" }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(apps: List<TvApp>, loaded: Boolean, recent: List<Pair<TvApp, Long>>, theme: CouchTheme, monochrome: Boolean,
                 config: LocalConfig, actions: SystemActions, launch: (TvApp) -> Unit,
                 temperature: String?, model: LauncherModel, speedEnabled: Boolean, settings: () -> Unit) {
    val firstFocus = initialFocusModifier(apps.map { it.packageName })
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // A quiet wash in place of the promotional carousel.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to theme.surface.copy(alpha = 0.55f), 0.6f to theme.background)))
    // Compose's TV default pivots every focused row to 30% from the top, which hides the top bar.
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
    LazyVerticalGrid(columns = GridCells.Fixed(Columns), modifier = Modifier.fillMaxSize(), state = grid,
        contentPadding = PaddingValues(start = Margin, end = Margin, top = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "bar", span = { GridItemSpan(maxLineSpan) }) {
            TopBar(theme, config, model, speedEnabled, actions, settings)
        }
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            if (recent.isEmpty()) Hero(theme, temperature) else JumpBackIn(recent, theme, monochrome, temperature, launch)
        }
        item(key = "label", span = { GridItemSpan(maxLineSpan) }) {
            Text("Your apps", color = theme.secondary, fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 14.dp))
        }
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            // Like Google TV, the top bar stays in view while the first row has focus.
            val pinTop = if (index < Columns) Modifier.onFocusChanged { if (it.isFocused) scope.launch { grid.animateScrollToItem(0) } }
                else Modifier
            AppTile(app, theme, monochrome, pinTop.then(if (index == 0) firstFocus else Modifier)) { launch(app) }
        }
        if (loaded && apps.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Text("No TV apps installed", color = theme.secondary, fontFamily = AppFont, fontSize = 17.sp)
        }
    }
    }
}

/** Scroll only as far as needed to show the focused item. */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

@Composable
private fun TopBar(theme: CouchTheme, config: LocalConfig, model: LauncherModel, speedEnabled: Boolean,
                   actions: SystemActions, settings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Focusable(theme, CircleShape, { actions.account() }, Modifier.size(40.dp).semantics { contentDescription = "Google account" },
            background = theme.badge) {
            val initial = config.profileInitial
            if (initial != null) Text(initial, color = theme.onBadge, fontFamily = AppFont, fontWeight = FontWeight.Medium,
                fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            else Image(painterResource(R.drawable.ic_account_circle), null, Modifier.align(Alignment.Center).size(26.dp),
                colorFilter = ColorFilter.tint(theme.onBadge))
        }
        Spacer(Modifier.width(14.dp))
        // Google TV's tab pill; Couch OS has a single page, so it is a label rather than a button.
        Box(Modifier.clip(RoundedCornerShape(50)).background(theme.badge).padding(horizontal = 18.dp, vertical = 7.dp)) {
            Text("Home", color = theme.onBadge, fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        }
        Spacer(Modifier.width(14.dp))
        Row(Modifier.clip(RoundedCornerShape(50)).background(theme.surface.copy(alpha = 0.7f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(R.drawable.ic_screensaver, "Screensaver", theme) { actions.screensaver() }
            IconButton(R.drawable.ic_settings, "Quick settings", theme, onClick = settings)
        }
        Spacer(Modifier.weight(1f))
        StatusLine(theme, model, speedEnabled)
    }
}

/**
 * Replaces Google TV's "time | Google TV" corner: time, network and live download speed. It samples on its
 * own every two seconds while HOME is visible, so only this line redraws, never the app grid.
 */
@Composable
private fun StatusLine(theme: CouchTheme, model: LauncherModel, speedEnabled: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by produceState(NetworkLabel(Link.OFFLINE, null) to (null as String?), speedEnabled, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.speed.reset()
            while (true) {
                val speed = if (speedEnabled) model.speed.sample() ?: value.second else null
                value = model.network.current() to speed
                delay(LiveSpeed.SAMPLE_MS)
            }
        }
    }
    val (network, speed) = status
    val now = rememberMinute()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(now.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)), color = theme.secondary, fontFamily = AppFont,
            fontSize = 20.sp)
        Text("  |  ", color = theme.secondary.copy(alpha = 0.6f), fontFamily = AppFont, fontSize = 20.sp)
        val icon = when (network.link) {
            Link.WIFI -> R.drawable.ic_wifi
            Link.ETHERNET -> R.drawable.ic_lan
            Link.OFFLINE -> R.drawable.ic_wifi_off
        }
        Image(painterResource(icon), contentDescription = network.link.name.lowercase(), Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(theme.secondary))
        val name = network.name ?: if (network.link == Link.OFFLINE) "Offline" else null
        if (name != null) Text(" $name", color = theme.secondary, fontFamily = AppFont, fontSize = 20.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (speed != null && network.link != Link.OFFLINE) {
            Spacer(Modifier.width(14.dp))
            Image(painterResource(R.drawable.ic_arrow_downward), contentDescription = "Download", Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(theme.secondary))
            Text(speed, color = theme.secondary, fontFamily = AppFont, fontSize = 20.sp)
        }
    }
}

@Composable
private fun rememberMinute(): ZonedDateTime {
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
    return now
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun dateLine(now: ZonedDateTime, temperature: String?) =
    listOfNotNull(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), temperature).joinToString("  •  ")

/** Where Google TV runs sponsored artwork: a greeting, the date and the weather. */
@Composable
private fun Hero(theme: CouchTheme, temperature: String?) {
    val now = rememberMinute()
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 48.dp)) {
        Text(greeting(now.hour), color = theme.text, fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 46.sp,
            lineHeight = 54.sp)
        Spacer(Modifier.height(12.dp))
        Text(dateLine(now, temperature), color = theme.secondary, fontFamily = AppFont, fontSize = 18.sp)
    }
}

/**
 * The carousel slot Google TV fills with promotions, used for the owner's recently used apps instead.
 * It never advances on its own: Left/Right flips while it has focus, OK opens the app.
 */
@Composable
private fun JumpBackIn(items: List<Pair<TvApp, Long>>, theme: CouchTheme, monochrome: Boolean, temperature: String?,
                       launch: (TvApp) -> Unit) {
    val now = rememberMinute()
    var index by remember(items.map { it.first.packageName }) { mutableIntStateOf(0) }
    val (app, at) = items[index.coerceIn(0, items.lastIndex)]
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${greeting(now.hour)}  •  ${dateLine(now, temperature)}", color = theme.secondary, fontFamily = AppFont,
                fontSize = 16.sp)
            Spacer(Modifier.height(22.dp))
            Text("JUMP BACK IN", color = theme.secondary, fontFamily = AppFont, fontWeight = FontWeight.Medium,
                fontSize = 12.sp, letterSpacing = 2.sp)
            Crossfade(app, animationSpec = tween(220), label = "jump-title") { shown ->
                Text(shown.label, color = theme.text, fontFamily = AppFont, fontSize = 46.sp, lineHeight = 56.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(RecentApps.ago(at, System.currentTimeMillis()), color = theme.secondary, fontFamily = AppFont, fontSize = 18.sp)
            if (items.size > 1) Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.indices.forEach { i ->
                    Box(Modifier.size(if (i == index) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == index) theme.text else theme.secondary.copy(alpha = 0.4f)))
                }
            }
        }
        Focusable(theme, RoundedCornerShape(12.dp), { launch(app) },
            Modifier.width(352.dp).aspectRatio(16f / 9f).semantics { contentDescription = "Jump back in: ${app.label}" }
                .onPreviewKeyEvent { event ->
                    if (items.size < 2 || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> { index = (index + 1) % items.size; true }
                        Key.DirectionLeft -> { index = (index - 1 + items.size) % items.size; true }
                        else -> false
                    }
                }) {
            Crossfade(app, animationSpec = tween(220), label = "jump-art") { shown ->
                val artwork by produceState(ArtworkCache.peek(shown), shown) {
                    if (value == null) value = withContext(Dispatchers.IO) { ArtworkCache.load(context, shown) }
                }
                artwork?.let {
                    Image(it.image, null, Modifier.fillMaxSize(), contentScale = if (it.wide) ContentScale.Crop else ContentScale.Fit,
                        colorFilter = if (monochrome) monochromeFilter(true, it.light) else null)
                }
            }
        }
    }
}

@Composable
fun Heading(label: String, theme: CouchTheme) = Text(label, color = theme.text, fontFamily = AppFont,
    fontWeight = FontWeight.Medium, fontSize = 30.sp)

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
                Text(app.label, color = theme.text, fontSize = 16.sp, fontFamily = AppFont, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
            }
            // Banners are designed edge to edge; square icons sit centred on the surface.
            artwork?.wide == true -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = if (monochrome) monochromeFilter(focused, light) else null)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(bitmap, null, Modifier.size(56.dp), contentScale = ContentScale.Fit,
                    colorFilter = if (monochrome) monochromeFilter(focused, false) else null)
            }
        }
    }
}

/** 16:9 tile; the app name appears beneath it while focused, as on Google TV. */
@Composable
private fun TileWithCaption(label: String, theme: CouchTheme, modifier: Modifier, onClick: () -> Unit,
                            content: @Composable BoxScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val captionAlpha by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 600f), label = "caption")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Focusable(theme, TileShape, onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = label },
            onFocus = { focused = it }) { content(focused) }
        Text(label, color = theme.text, fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp).graphicsLayer { alpha = captionAlpha })
    }
}

@Composable
private fun IconButton(@DrawableRes icon: Int, label: String, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(theme, CircleShape, onClick, modifier.size(36.dp).semantics { contentDescription = label },
        background = Color.Transparent) {
        Image(painterResource(icon), null, Modifier.align(Alignment.Center).size(20.dp), colorFilter = ColorFilter.tint(theme.text))
    }
}

/** One focus treatment everywhere: spring scale, focus-colour ring and a soft glow, drawn only while focused. */
@Composable
fun Focusable(theme: CouchTheme, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier,
              onFocus: (Boolean) -> Unit = {}, focusScale: Float = theme.focusScale, background: Color = theme.surface,
              content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f,
        spring(dampingRatio = 0.85f, stiffness = 500f), label = "focus")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind {
            if (focused) drawRoundRect(Brush.radialGradient(listOf(theme.focus.copy(alpha = 0.14f), Color.Transparent),
                center = center, radius = size.maxDimension * 0.7f),
                topLeft = Offset(-16.dp.toPx(), -16.dp.toPx()),
                size = Size(size.width + 32.dp.toPx(), size.height + 32.dp.toPx()),
                cornerRadius = CornerRadius(28.dp.toPx()))
        }
        .clip(shape).background(if (focused && background == Color.Transparent) theme.surface else background)
        .border(if (focused) theme.focusWidth.dp else 0.dp, if (focused) theme.focus else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick), content = content)
}

/** Settings row: label on the left, current value on the right. */
@Composable
private fun RowButton(label: String, value: String? = null, theme: CouchTheme, modifier: Modifier = Modifier,
                      badge: String? = null, @DrawableRes icon: Int? = null, width: Dp = 760.dp, onClick: () -> Unit) {
    // Wide rows only lift slightly; a full tile-size zoom would crowd the list.
    Focusable(theme, RoundedCornerShape(14.dp), onClick, modifier.width(width), focusScale = 1.02f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(theme.text))
                Spacer(Modifier.width(18.dp))
            }
            Text(label, color = theme.text, fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 18.sp,
                modifier = Modifier.weight(1f))
            // Badge sits inside the surface row so the focus ring keeps its validated contrast.
            if (badge != null) Text(badge, color = theme.onBadge, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(theme.badge)
                    .padding(horizontal = 10.dp, vertical = 3.dp))
            if (value != null) Text(value, color = theme.secondary, fontFamily = AppFont, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SectionLabel(label: String, theme: CouchTheme) = Text(label, color = theme.secondary, fontFamily = AppFont,
    fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp))

/** The gear panel: the TV shortcuts Google TV keeps behind its gear, plus this launcher's own options. */
@Composable
private fun LauncherSettings(themes: List<CouchTheme>, theme: CouchTheme, choose: (String) -> Unit,
                             speedEnabled: Boolean, setSpeed: (Boolean) -> Unit,
                             monochrome: Boolean, setMonochrome: (Boolean) -> Unit,
                             recentsEnabled: Boolean, setRecents: (Boolean) -> Unit,
                             config: LocalConfig, actions: SystemActions, back: () -> Unit) {
    val firstFocus = initialFocusModifier()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Margin), contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("Settings", theme) }
        item { SectionLabel("TV", theme) }
        item { RowButton("Inputs", null, theme, firstFocus, icon = R.drawable.ic_input) { actions.inputs(config) } }
        item { RowButton("Network & Internet", null, theme, icon = R.drawable.ic_wifi) { actions.wifi() } }
        item { RowButton("Google account", null, theme, icon = R.drawable.ic_account_circle) { actions.account() } }
        item { RowButton("All TV settings", null, theme, icon = R.drawable.ic_settings) { actions.settings() } }
        item { SectionLabel("HOME SCREEN", theme) }
        item { RowButton("Live download speed", if (speedEnabled) "On" else "Off", theme) { setSpeed(!speedEnabled) } }
        item { RowButton("App artwork", if (monochrome) "Monochrome" else "Colour", theme) { setMonochrome(!monochrome) } }
        item { RowButton("Jump back in", if (recentsEnabled) "On" else "Off", theme) { setRecents(!recentsEnabled) } }
        item { SectionLabel("THEME", theme) }
        items(themes, key = { it.id }) { option ->
            RowButton(option.name, theme = theme, badge = if (option.id == theme.id) "IN USE" else null) { choose(option.id) }
        }
        item {
            Text("Live speed shows how fast this TV is downloading right now and adds no traffic. Weather by Open-Meteo · CC BY 4.0",
                color = theme.secondary, fontFamily = AppFont, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
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
