package dev.couch.launcher

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
    val normalTheme = themes.find { it.id == themeId } ?: CouchTheme()
    val layout by model.homeLayout.collectAsStateWithLifecycle()
    val evening = layout.evening && layout.isEvening(rememberMinute().hour)
    val mediaEnabled by model.mediaEnabled.collectAsStateWithLifecycle()
    val homeRequest by model.homeRequest.collectAsStateWithLifecycle()
    val monochrome by model.monochrome.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val recentsEnabled by model.recentsEnabled.collectAsStateWithLifecycle()
    val watchNext by model.watchNext.collectAsStateWithLifecycle()
    val played by model.played.collectAsStateWithLifecycle()
    val untitledPlayback by model.untitledPlayback.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var appMenu by remember { mutableStateOf<TvApp?>(null) }
    var cardMenu by remember { mutableStateOf<WatchCard?>(null) }
    var pinApp by remember { mutableStateOf<TvApp?>(null) }
    var focusReset by remember { mutableIntStateOf(0) }
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) {
            appMenu = null
            cardMenu = null
            pinApp = null
            page = "home"
        }
    }
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
    val order = layout.favourites ?: config.favourites
    val visibleApps = remember(apps, layout.hiddenApps) { apps.filterNot { it.packageName in layout.hiddenApps } }
    val favourites = remember(visibleApps, order) { favouriteApps(visibleApps, order) }
    val allApps = remember(apps, recent) {
        val recentlyOpened = favouriteApps(apps, recent.map { it.first })
        recentlyOpened + apps.filterNot { it in recentlyOpened }
    }
    val cards = remember(apps, played, watchNext, layout, recentsEnabled, untitledPlayback) {
        if (recentsEnabled) watchCards(apps, played, watchNext, layout, untitledPlayback) else emptyList()
    }
    val launch: (TvApp) -> Unit = { model.launched(it); actions.launch(it) }
    val theme = if (evening && page == "home") themes.find { it.id == "midnight-mint" } ?: normalTheme else normalTheme

    BackHandler { page = "home" }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (page) {
                "settings" -> LauncherSettings(themes, theme, model, monochrome, recentsEnabled,
                    mediaEnabled, played.isNotEmpty() || untitledPlayback.isNotEmpty(), layout, config, actions) { page = "home" }
                "apps" -> AppGrid(allApps, layout, theme, monochrome, launch, { appMenu = it }) { page = "home" }
                else -> Home(favourites.ifEmpty { visibleApps }, loaded, cards, theme, monochrome, config,
                    actions, launch, temperature, model, evening, homeRequest, focusReset, layout,
                    { appMenu = it }, { cardMenu = it }, { page = "apps" },
                    showPinHint = recentsEnabled && mediaEnabled) { page = "settings" }
            }
        }
        appMenu?.let { app ->
            AppOptions(app, order, layout, theme, model::updateHome, { appMenu = null }) {
                appMenu = null
                pinApp = app
            }
        }
        pinApp?.let { app ->
            PinShow(app, theme, { pinApp = null }) { title ->
                model.updateHome { it.addPin(PlayedItem(app.packageName, title, null, System.currentTimeMillis(), null)) }
                model.setRecentsEnabled(true)
                pinApp = null
                page = "home"
                focusReset++
            }
        }
        cardMenu?.let { card ->
            OptionsDialog(card.title, theme, { cardMenu = null }) { firstFocus ->
                RowButton(if (card.pin == null) "Pin show" else "Unpin show", theme = theme, modifier = firstFocus) {
                    model.updateHome {
                        if (card.pin != null) it.removePin(card.key)
                        else it.addPin(PlayedItem(card.app.packageName, card.title, null, System.currentTimeMillis(), null))
                    }
                    cardMenu = null
                }
                RowButton("Hide from carousel", theme = theme) {
                    model.updateHome { it.hideShow(card.key) }
                    cardMenu = null
                }
                RowButton("Mark watched", theme = theme) {
                    model.updateHome { it.markWatched(card.key, System.currentTimeMillis()) }
                    cardMenu = null
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(apps: List<TvApp>, loaded: Boolean, cards: List<WatchCard>, theme: CouchTheme, monochrome: Boolean,
                 config: LocalConfig, actions: SystemActions, launch: (TvApp) -> Unit,
                 temperature: String?, model: LauncherModel, evening: Boolean, homeRequest: Int,
                 focusReset: Int, layout: HomePreferences, appMenu: (TvApp) -> Unit, cardMenu: (WatchCard) -> Unit,
                 allApps: () -> Unit, showPinHint: Boolean, settings: () -> Unit) {
    val cardFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val focusCarousel = evening && cards.isNotEmpty()
    val firstFocus = initialFocusModifier(apps.firstOrNull()?.packageName to focusReset, gridFocus, enabled = loaded && !focusCarousel)
    val firstCardFocus = initialFocusModifier(focusCarousel, cardFocus, enabled = focusCarousel)
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val upToCard = if (cards.isEmpty()) Modifier else Modifier.focusProperties { up = cardFocus }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to theme.surface.copy(alpha = 0.55f), 0.6f to theme.background)))
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyVerticalGrid(columns = GridCells.Fixed(Columns), modifier = Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = Margin, end = Margin, top = 32.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "bar", span = { GridItemSpan(maxLineSpan) }) {
                TopBar(theme, config, model.network, actions, settings)
            }
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                if (cards.isEmpty()) Hero(theme, temperature, showPinHint && apps.isNotEmpty())
                else JumpBackIn(cards, theme, temperature, model.watchNextSource,
                    firstCardFocus.focusProperties { down = gridFocus }, homeRequest, layout.onlineArtwork, cardMenu) { card ->
                    model.launched(card.app)
                    actions.openCard(card)
                }
            }
            item(key = "label", span = { GridItemSpan(maxLineSpan) }) {
                Text("Your apps", color = theme.secondary, fontFamily = AppFont, fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 14.dp))
            }
            itemsIndexed(apps.take(Columns), key = { _, app -> app.packageName }) { index, app ->
                val top = upToCard.onFocusChanged {
                    if (it.hasFocus && grid.firstVisibleItemIndex > 0) scope.launch { grid.animateScrollToItem(0) }
                }
                AppTile(app, theme, monochrome, top.then(if (index == 0) firstFocus else Modifier),
                    onLongClick = { appMenu(app) }) { launch(app) }
            }
            item(key = "all-apps") {
                val focus = if (apps.isEmpty()) upToCard.then(firstFocus) else if (apps.size < Columns) upToCard else Modifier
                HomeShortcut("All apps", R.drawable.ic_apps, theme, focus, allApps)
            }
            if (layout.inputsPinned) item(key = "inputs-shortcut") {
                HomeShortcut("Inputs", R.drawable.ic_input, theme) { actions.inputs(config) }
            }
            if (layout.ambientPinned) item(key = "ambient-shortcut") {
                HomeShortcut("Google Photos Ambient", R.drawable.ic_screensaver, theme) { actions.screensaver() }
            }
            itemsIndexed(apps.drop(Columns), key = { _, app -> app.packageName }) { _, app ->
                AppTile(app, theme, monochrome, onLongClick = { appMenu(app) }) { launch(app) }
            }
        }
    }
}

@Composable
private fun HomeShortcut(label: String, @DrawableRes icon: Int, theme: CouchTheme,
                         modifier: Modifier = Modifier, open: () -> Unit) {
    TileWithCaption(label, theme, modifier, open) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(icon), null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(theme.text))
            Text(label, color = theme.text, fontFamily = AppFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppGrid(apps: List<TvApp>, layout: HomePreferences, theme: CouchTheme, monochrome: Boolean,
                    launch: (TvApp) -> Unit, appMenu: (TvApp) -> Unit, back: () -> Unit) {
    val firstFocus = initialFocusModifier()
    LazyVerticalGrid(columns = GridCells.Fixed(Columns),
        contentPadding = PaddingValues(horizontal = Margin, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Heading("All apps", theme)
                Text("Hold OK to manage an app or pin a show", color = theme.secondary, fontFamily = AppFont,
                    fontSize = 16.sp, modifier = Modifier.padding(bottom = 18.dp))
            }
        }
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val hidden = app.packageName in layout.hiddenApps
            AppTile(app, theme, monochrome, if (index == 0) firstFocus else Modifier,
                label = if (hidden) "${app.label} (hidden)" else app.label,
                onLongClick = { appMenu(app) }) { if (hidden) appMenu(app) else launch(app) }
        }
        item { RowButton("Back", theme = theme, modifier = if (apps.isEmpty()) firstFocus else Modifier, onClick = back) }
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
private fun TopBar(theme: CouchTheme, config: LocalConfig, network: NetworkStatus,
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
        StatusLine(theme, network)
        if (config.spokenBacklight != null || config.backlight != null) Spacer(Modifier.width(12.dp))
        when {
            config.spokenBacklight != null -> SpokenBacklightButton(config.spokenBacklight, theme)
            config.backlight != null -> BacklightButton(config.backlight, theme)
        }
    }
}

@Composable
private fun SpokenBacklightButton(name: String, theme: CouchTheme) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("spoken_backlight", android.content.Context.MODE_PRIVATE) }
    var lastOn by remember(name, preferences) {
        mutableStateOf(preferences.getBoolean(name, false).takeIf { preferences.contains(name) })
    }
    var speech by remember(context, name) { mutableStateOf<BacklightSpeech?>(null) }
    DisposableEffect(context, name) {
        val controller = BacklightSpeech(context, name) { on ->
            lastOn = on
            preferences.edit().putBoolean(name, on).apply()
        }
        speech = controller
        onDispose { controller.close() }
    }
    Focusable(theme, CircleShape, { speech?.speak(lastOn != true) },
        Modifier.size(36.dp).semantics {
            contentDescription = name
            stateDescription = when (lastOn) {
                true -> "Last command on"
                false -> "Last command off"
                null -> "No command yet"
            }
        },
        background = if (lastOn == true) Color(0xFFFFD36B) else Color.Transparent,
        onLongClick = { speech?.speak(false) }) {
        Image(painterResource(R.drawable.ic_lightbulb), null, Modifier.align(Alignment.Center).size(22.dp),
            colorFilter = ColorFilter.tint(if (lastOn == true) Color(0xFF241900) else theme.secondary))
    }
}

@Composable
private fun BacklightButton(config: BacklightConfig, theme: CouchTheme) {
    val client = remember(config) { BacklightClient(config) }
    val operations = remember(client) { Mutex() }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var on by remember(client) { mutableStateOf<Boolean?>(null) }
    var changing by remember(client) { mutableStateOf(false) }
    LaunchedEffect(client, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                operations.withLock { on = withContext(Dispatchers.IO) { client.state() } }
                delay(10_000)
            }
        }
    }
    val state = when {
        changing -> "Changing"
        on == true -> "On"
        on == false -> "Off"
        else -> "Unavailable"
    }
    Focusable(theme, CircleShape, {
        if (!changing) {
            changing = true
            scope.launch {
                try {
                    operations.withLock { on = withContext(Dispatchers.IO) { client.toggle() } }
                } finally {
                    changing = false
                }
            }
        }
    }, Modifier.size(36.dp).semantics { contentDescription = "TV Smart Light: $state" },
        background = if (on == true) theme.badge else Color.Transparent) {
        Image(painterResource(R.drawable.ic_lightbulb), null, Modifier.align(Alignment.Center).size(22.dp),
            colorFilter = ColorFilter.tint(if (on == true) theme.onBadge else theme.text))
    }
}

/** Refresh network name and signal while Home is visible. */
@Composable
private fun StatusLine(theme: CouchTheme, networkStatus: NetworkStatus) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val network by produceState(NetworkLabel(Link.OFFLINE, null), networkStatus, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { networkStatus.current() }
                delay(5_000)
            }
        }
    }
    val now = rememberMinute()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(now.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)), color = theme.secondary, fontFamily = AppFont,
            fontSize = 20.sp)
        Text("  |  ", color = theme.secondary.copy(alpha = 0.6f), fontFamily = AppFont, fontSize = 20.sp)
        if (network.link == Link.WIFI) {
            WifiSignalIcon(network.strength, theme.secondary)
        } else {
            val icon = if (network.link == Link.ETHERNET) R.drawable.ic_lan else R.drawable.ic_wifi_off
            Image(painterResource(icon), contentDescription = network.link.name.lowercase(), Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(theme.secondary))
        }
        val name = network.name ?: if (network.link == Link.OFFLINE) "Offline" else null
        if (name != null) Text(" $name", color = theme.secondary, fontFamily = AppFont, fontSize = 20.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

@Composable
private fun WifiSignalIcon(strength: Float?, color: Color) {
    val label = strength?.let { "Wi-Fi signal ${(it * 100).roundToInt()} percent" } ?: "Wi-Fi connected"
    Canvas(Modifier.size(20.dp).semantics { contentDescription = label }) {
        val w = size.width
        val h = size.height
        val fan = Path().apply {
            moveTo(w * 0.07f, h * 0.28f)
            quadraticTo(w * 0.5f, -h * 0.04f, w * 0.93f, h * 0.28f)
            quadraticTo(w * 0.97f, h * 0.31f, w * 0.93f, h * 0.36f)
            lineTo(w * 0.55f, h * 0.84f)
            quadraticTo(w * 0.5f, h * 0.9f, w * 0.45f, h * 0.84f)
            lineTo(w * 0.07f, h * 0.36f)
            quadraticTo(w * 0.03f, h * 0.31f, w * 0.07f, h * 0.28f)
            close()
        }
        drawPath(fan, color.copy(alpha = 0.45f))
        if (strength != null) {
            val fillTop = h * (0.87f - 0.75f * strength)
            clipRect(top = fillTop) { drawPath(fan, color) }
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
private fun Hero(theme: CouchTheme, temperature: String?, showPinHint: Boolean) {
    val now = rememberMinute()
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 48.dp)) {
        Text(greeting(now.hour), color = theme.text, fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 46.sp,
            lineHeight = 54.sp)
        Spacer(Modifier.height(12.dp))
        Text(dateLine(now, temperature), color = theme.secondary, fontFamily = AppFont, fontSize = 18.sp)
        if (showPinHint) Text("Hold OK on an app to pin a show to Continue Watching.",
            color = theme.secondary, fontFamily = AppFont, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Home carousel with artwork and episode details from on-device sources. */
@Composable
internal fun JumpBackIn(cards: List<WatchCard>, theme: CouchTheme, temperature: String?,
                        watchNext: WatchNextSource, modifier: Modifier = Modifier, homeRequest: Int = 0,
                        onlineArtwork: Boolean, options: (WatchCard) -> Unit, open: (WatchCard) -> Unit) {
    val now = rememberMinute()
    val keys = cards.map { it.key }
    var index by remember(keys, homeRequest) { mutableIntStateOf(0) }
    val card = cards[index]
    val context = LocalContext.current
    val canResume by key(card.key, card.livePlayback, homeRequest) {
        produceState(false) {
            value = withContext(Dispatchers.IO) {
                card.livePlayback?.let { MediaHistory.activeSession(context, it) }?.let(MediaHistory::canResume) == true
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Text("${greeting(now.hour)}  •  ${dateLine(now, temperature)}", color = theme.secondary, fontFamily = AppFont,
                fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Text("CONTINUE WATCHING", color = theme.secondary, fontFamily = AppFont,
                fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 2.sp)
            Crossfade(card, animationSpec = tween(180), label = "watch-title") { shown ->
                Column {
                    Text(shown.title, color = theme.text, fontFamily = AppFont, fontSize = 42.sp,
                        lineHeight = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(shown.app.label, shown.episode).joinToString("  •  "),
                        color = theme.secondary, fontFamily = AppFont, fontSize = 18.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val actionLabel = when {
                        shown.linkedShow?.intentUri != null -> "Continue in ${shown.app.label}"
                        shown.key == card.key && canResume -> "Resume in ${shown.app.label}"
                        else -> "Open ${shown.app.label}"
                    }
                    Text(actionLabel, color = theme.text, fontFamily = AppFont, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            Text("${index + 1} / ${cards.size}  •  Hold OK for options", color = theme.secondary,
                fontFamily = AppFont, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Focusable(theme, RoundedCornerShape(12.dp), { open(card) },
            modifier.width(416.dp).aspectRatio(16f / 9f)
                .semantics { contentDescription = "Continue watching: " + card.title }
                .onPreviewKeyEvent { event ->
                    if (cards.size < 2 || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> { index = (index + 1) % cards.size; true }
                        Key.DirectionLeft -> { index = (index - 1 + cards.size) % cards.size; true }
                        else -> false
                    }
                }, onLongClick = { options(card) }) {
            Crossfade(card, animationSpec = tween(180), label = "watch-art") { shown ->
                WatchArtwork(shown, theme, watchNext, onlineArtwork)
            }
            card.progress?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .height(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(theme.focus))
                }
            }
        }
    }
}

@Composable
private fun WatchArtwork(card: WatchCard, theme: CouchTheme, watchNext: WatchNextSource, onlineArtwork: Boolean) {
    val context = LocalContext.current
    val played = card.played
    val candidates = listOfNotNull(played?.art, card.show?.thumbnail, card.show?.poster).distinct()
    val poster by key(card.key, onlineArtwork, candidates, played?.mediaId, played?.subtitle) {
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                val local = played?.let { MediaHistory.artFile(context, it) }
                (local?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
                    ?: watchNext.poster(candidates, onlineArtwork))?.asImageBitmap()
            }
        }
    }
    val image = poster
    if (image == null) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(theme.surface, theme.background))),
            contentAlignment = Alignment.Center) {
            Text(card.title, color = theme.text, fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(24.dp))
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(image, "Artwork for ${card.title}", Modifier.fillMaxSize(),
                contentScale = if (image.width >= image.height * 1.35) ContentScale.Crop else ContentScale.Fit)
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
private fun AppTile(app: TvApp, theme: CouchTheme, monochrome: Boolean, modifier: Modifier = Modifier,
                    label: String = app.label, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    // Tiles render at once; each banner decodes off the main thread and is cached across visits.
    val artwork by produceState(ArtworkCache.peek(app), app) {
        if (value == null) value = withContext(Dispatchers.IO) { ArtworkCache.load(context, app) }
    }
    val bitmap = artwork?.image
    val light = artwork?.light == true
    TileWithCaption(label, theme, modifier, onClick, onLongClick) { focused ->
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
                            onLongClick: (() -> Unit)? = null, content: @Composable BoxScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val captionAlpha by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 600f), label = "caption")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Focusable(theme, TileShape, onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = label },
            onFocus = { focused = it }, onLongClick = onLongClick) { content(focused) }
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
              onLongClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val remoteHold = if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { event ->
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) {
                    val held = event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime
                    if (held >= longPressTimeout) onLongClick() else onClick()
                }
                true
            }
            else -> false
        }
    }
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
        .then(remoteHold)
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick, onLongClick = onLongClick), content = content)
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
private fun LauncherSettings(themes: List<CouchTheme>, theme: CouchTheme, model: LauncherModel,
                             monochrome: Boolean, recentsEnabled: Boolean,
                             mediaEnabled: Boolean, hasHistory: Boolean, layout: HomePreferences,
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
        item { RowButton("App artwork", if (monochrome) "Monochrome" else "Colour", theme) { model.setMonochrome(!monochrome) } }
        item { RowButton("Continue watching", if (recentsEnabled) "On" else "Off", theme) { model.setRecentsEnabled(!recentsEnabled) } }
        if (hasHistory) item { RowButton("Clear watch history", null, theme) { model.clearHistory() } }
        item { SectionLabel("THEME", theme) }
        items(themes, key = { it.id }) { option ->
            RowButton(option.name, theme = theme, badge = if (option.id == theme.id) "IN USE" else null) { model.selectTheme(option.id) }
        }
        item { SectionLabel("PERSONALISE", theme) }
        item { RowButton("Automatic watch history", if (mediaEnabled) "Allowed" else "Off", theme) { actions.mediaAccess() } }
        item { RowButton("Download show artwork", if (layout.onlineArtwork) "On" else "Off", theme) {
            model.updateHome { it.copy(onlineArtwork = !it.onlineArtwork) }
        } }
        item { RowButton("Pin Inputs to Home", if (layout.inputsPinned) "On" else "Off", theme) {
            model.updateHome { it.copy(inputsPinned = !it.inputsPinned) }
        } }
        item { RowButton("Pin Google Photos Ambient to Home", if (layout.ambientPinned) "On" else "Off", theme) {
            model.updateHome { it.copy(ambientPinned = !it.ambientPinned) }
        } }
        item { RowButton("Quiet evening layout", if (layout.evening) "On" else "Off", theme) {
            model.updateHome { it.copy(evening = !it.evening) }
        } }
        if (layout.evening) item {
            val hour = layout.eveningHour
            RowButton("Evening starts (OK to change)", "${hour - 12}:00 PM", theme) {
                model.updateHome { it.copy(eveningHour = if (hour == 23) 17 else hour + 1) }
            }
        }
        if (layout.hiddenShows.isNotEmpty() || layout.watchedShows.isNotEmpty()) item {
            RowButton("Restore hidden and watched titles", null, theme) {
                model.updateHome { it.copy(hiddenShows = emptySet(), watchedShows = emptyMap()) }
            }
        }
        item {
            Text("Weather by Open-Meteo · CC BY 4.0",
                color = theme.secondary, fontFamily = AppFont, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        item { RowButton("Back", null, theme, onClick = back) }
    }
}


@Composable
private fun OptionsDialog(title: String, theme: CouchTheme, close: () -> Unit,
                          content: @Composable ColumnScope.(Modifier) -> Unit) {
    val firstFocus = initialFocusModifier()
    Dialog(onDismissRequest = close) {
        Column(Modifier.width(540.dp).clip(RoundedCornerShape(18.dp)).background(theme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = theme.text, fontFamily = AppFont, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            content(firstFocus)
            RowButton("Cancel", theme = theme, onClick = close)
        }
    }
}

@Composable
private fun AppOptions(app: TvApp, order: List<String>, layout: HomePreferences, theme: CouchTheme,
                       update: ((HomePreferences) -> HomePreferences) -> Unit, close: () -> Unit, pinShow: () -> Unit) {
    val pkg = app.packageName
    val pinned = pkg in order
    val hidden = pkg in layout.hiddenApps
    OptionsDialog(app.label, theme, close) { firstFocus ->
        if (hidden) {
            RowButton("Show on Home", theme = theme, modifier = firstFocus) {
                update { it.copy(hiddenApps = it.hiddenApps - pkg) }
                close()
            }
        } else {
            RowButton(if (pinned) "Unpin from favourites" else "Pin to favourites", theme = theme, modifier = firstFocus) {
                update { it.copy(favourites = if (pinned) order - pkg else order + pkg) }
                close()
            }
            if (pinned && order.firstOrNull() != pkg) RowButton("Move to front", theme = theme) {
                update { it.copy(favourites = listOf(pkg) + (order - pkg)) }
                close()
            }
            RowButton("Pin a show…", theme = theme, onClick = pinShow)
            RowButton("Hide from Home", theme = theme) {
                update { it.copy(hiddenApps = it.hiddenApps + pkg) }
                close()
            }
        }
    }
}

@Composable
private fun PinShow(app: TvApp, theme: CouchTheme, close: () -> Unit, save: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    OptionsDialog("Pin a show from ${app.label}", theme, close) { firstFocus ->
        BasicTextField(value = title, onValueChange = { title = it.take(120) }, singleLine = true,
            textStyle = TextStyle(color = theme.text, fontFamily = AppFont, fontSize = 20.sp),
            cursorBrush = SolidColor(theme.focus),
            modifier = firstFocus.fillMaxWidth().semantics { contentDescription = "Show title" },
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth().clip(TileShape).background(theme.surface).padding(16.dp)) {
                    if (title.isBlank()) Text("Show title", color = theme.secondary, fontFamily = AppFont, fontSize = 20.sp)
                    field()
                }
            })
        if (title.isNotBlank()) RowButton("Save to carousel", theme = theme) { save(title.trim()) }
    }
}

/** Lazy children attach after the parent's composition effects; wait for placement. */
@Composable
private fun initialFocusModifier(key: Any? = Unit, requester: FocusRequester = remember { FocusRequester() },
                                 enabled: Boolean = true): Modifier {
    var requested by remember(key, enabled) { mutableStateOf(false) }
    return Modifier.focusRequester(requester).onGloballyPositioned {
        if (enabled && !requested) {
            requester.requestFocus()
            requested = true
        }
    }
}
