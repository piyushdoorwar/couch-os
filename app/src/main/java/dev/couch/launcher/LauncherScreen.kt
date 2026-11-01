package dev.couch.launcher

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LauncherScreen(model: LauncherModel, actions: SystemActions) {
    val apps by model.apps.collectAsStateWithLifecycle()
    val config by model.config.collectAsStateWithLifecycle()
    val themes by model.themes.collectAsStateWithLifecycle()
    val themeId by model.themeId.collectAsStateWithLifecycle()
    val theme = themes.find { it.id == themeId } ?: CouchTheme()
    val homeRequest by model.homeRequest.collectAsStateWithLifecycle()
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
    val favourites = remember(apps, config.favourites) { favouriteApps(apps, config.favourites) }
    // Refresh when returning from an app or system settings. No package inventory telemetry.
    LifecycleStartEffect(Unit) { model.refresh(); onStopOrDispose {} }
    BackHandler { page = "home" }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (page) {
                "apps" -> AppGrid(apps.filter { it !in favourites }, theme, actions) { page = "home" }
                "settings" -> ThemeSettings(themes, theme, model::selectTheme, actions) { page = "home" }
                else -> Home(favourites, theme, config, actions, temperature, { page = "apps" }, { page = "settings" })
            }
        }
    }
}

@Composable
private fun Home(favourites: List<TvApp>, theme: CouchTheme, config: LocalConfig, actions: SystemActions, temperature: String?,
                 allApps: () -> Unit, settings: () -> Unit) {
    val firstFocus = initialFocusModifier(favourites.map { it.packageName })
    Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            Text("COUCH OS", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionTile("Settings", theme, onClick = settings)
                ActionTile("Wi-Fi", theme) { actions.wifi() }
                ActionTile("Inputs", theme) { actions.inputs(config) }
            }
        }
        Spacer(Modifier.weight(1f))
        ClockHero(theme, temperature)
        Spacer(Modifier.height(20.dp))
        Text("YOUR FAVOURITES", color = theme.secondary, fontSize = 13.sp, letterSpacing = 2.sp,
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(favourites, key = { it.packageName }) { app ->
                AppTile(app, theme, if (app == favourites.first()) firstFocus else Modifier) { actions.launch(app) }
            }
            item(key = "all-apps") {
                FocusTile(theme, allApps, Modifier.width(184.dp).then(if (favourites.isEmpty()) firstFocus else Modifier)) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        Text("⊞", color = theme.focus, fontSize = 40.sp)
                    }
                    Text("All apps", color = theme.text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun ClockHero(theme: CouchTheme, temperature: String? = null) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)), color = theme.text,
                fontFamily = Poppins, fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 86.sp)
            if (temperature != null) Text(temperature, color = theme.text, fontFamily = Poppins,
                fontWeight = FontWeight.SemiBold, fontSize = 27.sp)
        }
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), color = theme.secondary,
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    }
}

@Composable
private fun AppGrid(apps: List<TvApp>, theme: CouchTheme, actions: SystemActions, back: () -> Unit) {
    val firstFocus = initialFocusModifier()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("All apps", theme)
            ActionTile("Back", theme, firstFocus, back)
        }
        if (apps.isEmpty()) Text("No other TV apps installed", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 32.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(184.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(apps, key = { it.packageName }) { app -> AppTile(app, theme) { actions.launch(app) } }
        }
    }
}

@Composable
fun Heading(label: String, theme: CouchTheme) = Text(label, color = theme.text, fontFamily = Poppins,
    fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)

@Composable
fun ActionTile(label: String, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusTile(theme, onClick, modifier) {
        Text(label, color = theme.text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun AppTile(app: TvApp, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = remember(app.artwork) {
        app.artwork?.let { drawable ->
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 320
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
            val factor = minOf(1f, 512f / maxOf(width, height))
            drawable.toBitmap((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1)).asImageBitmap()
        }
    }
    FocusTile(theme, onClick, modifier.width(184.dp)) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = theme.text, fontSize = 32.sp, fontFamily = Poppins, fontWeight = FontWeight.ExtraBold)
        }
        Text(app.label, color = theme.text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
fun FocusTile(theme: CouchTheme, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) theme.focusScale else 1f,
        spring(dampingRatio = 0.85f, stiffness = 500f), label = "focus")
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind {
            if (focused) drawRoundRect(Brush.radialGradient(listOf(theme.focus.copy(alpha = 0.20f), Color.Transparent),
                center = center, radius = size.maxDimension * 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(-12.dp.toPx(), -12.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width + 24.dp.toPx(), size.height + 24.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
        }
        .border(if (focused) theme.focusWidth.dp else 0.dp, if (focused) theme.focus else Color.Transparent, shape)
        .clip(shape).background(theme.surface)
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick)
        .padding(6.dp), content = content)
}


@Composable
private fun ThemeSettings(themes: List<CouchTheme>, theme: CouchTheme, choose: (String) -> Unit,
                          actions: SystemActions, back: () -> Unit) {
    val firstFocus = initialFocusModifier(themes.map { it.id })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading("Make yourself at home", theme) }
        item { Text("APPEARANCE", color = theme.secondary, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp) }
        items(themes, key = { it.id }) { option ->
            ActionTile((if (option.id == theme.id) "✓  " else "") + option.name, theme,
                if (option == themes.first()) firstFocus else Modifier) { choose(option.id) }
        }
        item { ActionTile("TV settings", theme) { actions.settings() } }
        item { Text("Weather by Open-Meteo · CC BY 4.0", color = theme.secondary, fontFamily = Poppins,
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
        item { ActionTile("Back", theme, onClick = back) }
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
