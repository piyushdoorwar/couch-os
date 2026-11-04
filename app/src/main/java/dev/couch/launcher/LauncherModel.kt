package dev.couch.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherModel(app: Application) : AndroidViewModel(app) {
    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps = _apps.asStateFlow()
    /** False until the first app scan finishes, so an empty grid is never mistaken for "no apps". */
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private val homeStore = HomePreferenceStore(app)
    private val _homeLayout = MutableStateFlow(homeStore.load())
    internal val homeLayout = _homeLayout.asStateFlow()
    internal fun updateHome(change: (HomePreferences) -> HomePreferences) {
        val updated = change(_homeLayout.value)
        homeStore.save(updated)
        _homeLayout.value = updated
    }
    private val _mediaEnabled = MutableStateFlow(MediaHistory.enabled(app))
    val mediaEnabled = _mediaEnabled.asStateFlow()
    private val recents = RecentApps(app)
    private val _recent = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val recent = _recent.asStateFlow()
    val watchNextSource = WatchNextSource(app)
    private val _played = MutableStateFlow<List<PlayedItem>>(emptyList())
    val played = _played.asStateFlow()
    private val _untitledPlayback = MutableStateFlow<Map<String, Long>>(emptyMap())
    val untitledPlayback = _untitledPlayback.asStateFlow()
    private var watchNextRefresh: Job? = null
    init {
        viewModelScope.launch {
            MediaHistory.updates.collectLatest {
                _mediaEnabled.value = MediaHistory.enabled(app)
                val (titles, untitled) = withContext(Dispatchers.IO) {
                    MediaHistory.load(app) to MediaHistory.untitledPlayback(app)
                }
                _played.value = titles
                _untitledPlayback.value = untitled
            }
        }
    }

    fun clearHistory() = viewModelScope.launch {
        withContext(Dispatchers.IO) { MediaHistory.clear(getApplication()) }
    }
    private val _watchNext = MutableStateFlow<List<WatchNextItem>>(emptyList())
    val watchNext = _watchNext.asStateFlow()
    private val _config = MutableStateFlow(LocalConfig())
    val config = _config.asStateFlow()
    private val _themes = MutableStateFlow(listOf(CouchTheme()))
    val themes = _themes.asStateFlow()
    private val _themeId = MutableStateFlow("classic")
    val themeId = _themeId.asStateFlow()
    val weather = WeatherRepository(app)
    val network = NetworkStatus(app)
    private val preferences = app.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val _monochrome = MutableStateFlow(preferences.getBoolean("monochrome", false))
    val monochrome = _monochrome.asStateFlow()
    fun setMonochrome(enabled: Boolean) {
        _monochrome.value = enabled
        preferences.edit().putBoolean("monochrome", enabled).apply()
    }
    private val _recentsEnabled = MutableStateFlow(preferences.getBoolean("recents", true))
    val recentsEnabled = _recentsEnabled.asStateFlow()
    fun setRecentsEnabled(enabled: Boolean) {
        _recentsEnabled.value = enabled
        preferences.edit().putBoolean("recents", enabled).apply()
    }
    fun launched(app: TvApp) = recents.recordLaunch(app.packageName)
    private val _homeRequest = MutableStateFlow(0)
    val homeRequest = _homeRequest.asStateFlow()
    fun showHome() { _homeRequest.value += 1 }
    fun selectTheme(id: String) {
        if (_themes.value.any { it.id == id }) {
            _themeId.value = id
            preferences.edit().putString("theme", id).apply()
        }
    }
    // Themes, config and the app scan are independent; load them side by side so tiles appear sooner.
    fun refresh() {
        val context = getApplication<Application>()
        if (!MediaHistory.enabled(context)) {
            _played.value = emptyList()
            _untitledPlayback.value = emptyMap()
        }
        MediaHistory.invalidate()
        viewModelScope.launch {
            _themes.value = withContext(Dispatchers.IO) { ThemeAssets.load(context) }
            _themeId.value = preferences.getString("theme", "classic") ?: "classic"
        }
        viewModelScope.launch { _config.value = withContext(Dispatchers.IO) { LocalConfig.read(context) } }
        refreshWatchNext()
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { AppCatalog(context).load() }
            _apps.value = apps
            _loaded.value = true
            // Decode every banner up front (favourites first), so scrolling never waits on a decode.
            launch(Dispatchers.IO) {
                val favourites = _config.value.favourites.toSet()
                apps.sortedBy { it.packageName !in favourites }.forEach { ArtworkCache.load(context, it) }
            }
            _recent.value = withContext(Dispatchers.IO) { recents.recent(apps.map { it.packageName }.toSet()) }
        }
    }

    fun refreshWatchNext() {
        watchNextRefresh?.cancel()
        watchNextRefresh = viewModelScope.launch {
            _watchNext.value = withContext(Dispatchers.IO) { watchNextSource.load() }
        }
    }
}
