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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
    val theme = CouchTheme()
    var page by remember { mutableStateOf("home") }
    val favourites = remember(apps, config.favourites) { favouriteApps(apps, config.favourites) }
    // Refresh when returning from an app or system settings. No package inventory telemetry.
    LifecycleStartEffect(Unit) { model.refresh(); onStopOrDispose {} }
    BackHandler { page = "home" }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (page) {
                "apps" -> AppGrid(apps.filter { it !in favourites }, theme, actions) { page = "home" }
                "settings" -> Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Heading("Settings", theme)
                    Text("Couch OS · private by default", color = theme.secondary, fontFamily = Poppins)
                    ActionTile("TV settings", theme) { actions.settings() }
                    ActionTile("Back", theme) { page = "home" }
                }
                else -> Home(favourites, theme, config, actions, { page = "apps" }, { page = "settings" })
            }
        }
    }
}

@Composable
private fun Home(favourites: List<TvApp>, theme: CouchTheme, config: LocalConfig, actions: SystemActions,
                 allApps: () -> Unit, settings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(favourites) { first.requestFocus() }
    Column(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            Text("COUCH OS", color = theme.secondary, fontFamily = Poppins, fontSize = 14.sp,
                letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionTile("Settings", theme, onClick = settings)
                ActionTile("Wi-Fi", theme) { actions.wifi() }
                ActionTile("Inputs", theme) { actions.inputs(config) }
            }
        }
        Spacer(Modifier.weight(1f))
        ClockHero(theme)
        Spacer(Modifier.height(38.dp))
        Text("YOUR FAVOURITES", color = theme.secondary, fontSize = 13.sp, letterSpacing = 2.sp,
            fontFamily = Poppins, modifier = Modifier.padding(start = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(favourites, key = { it.packageName }) { app ->
                AppTile(app, theme, if (app == favourites.first()) Modifier.focusRequester(first) else Modifier) { actions.launch(app) }
            }
            item(key = "all-apps") {
                FocusTile(theme, allApps, if (favourites.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                    Box(Modifier.size(184.dp, 104.dp), contentAlignment = Alignment.Center) {
                        Text("⊞", color = theme.focus, fontSize = 40.sp)
                    }
                    Text("All apps", color = theme.text, fontFamily = Poppins, fontSize = 16.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
fun ClockHero(theme: CouchTheme, temperature: String? = null) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Column(Modifier.padding(horizontal = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)), color = theme.text,
                fontFamily = Poppins, fontWeight = FontWeight.Black, fontSize = 80.sp, lineHeight = 96.sp)
            if (temperature != null) Text(temperature, color = theme.text, fontFamily = Poppins, fontSize = 27.sp)
        }
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), color = theme.secondary,
            fontFamily = Poppins, fontSize = 22.sp)
    }
}

@Composable
private fun AppGrid(apps: List<TvApp>, theme: CouchTheme, actions: SystemActions, back: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("All apps", theme)
            ActionTile("Back", theme, Modifier.focusRequester(first), back)
        }
        if (apps.isEmpty()) Text("No other TV apps installed", color = theme.secondary, fontFamily = Poppins, modifier = Modifier.padding(top = 32.dp))
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
        Text(label, color = theme.text, fontFamily = Poppins, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun AppTile(app: TvApp, theme: CouchTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = remember(app.artwork) { app.artwork?.toBitmap(320, 180)?.asImageBitmap() }
    FocusTile(theme, onClick, modifier.width(184.dp)) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(184.dp, 104.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.size(184.dp, 104.dp), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = theme.text, fontSize = 32.sp, fontFamily = Poppins)
        }
        Text(app.label, color = theme.text, fontFamily = Poppins, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
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
                center = center, radius = size.maxDimension * 0.8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))
        }
        .border(if (focused) theme.focusWidth.dp else 0.dp, if (focused) theme.focus else Color.Transparent, shape)
        .clip(shape).background(theme.surface)
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick), content = content)
}
