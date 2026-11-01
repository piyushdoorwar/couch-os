package dev.couch.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
    val recents = RecentApps(app)
    private val _recent = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val recent = _recent.asStateFlow()
    val watchNextSource = WatchNextSource(app)
    private val _watchNext = MutableStateFlow<List<WatchNextItem>>(emptyList())
    val watchNext = _watchNext.asStateFlow()
    private val _config = MutableStateFlow(LocalConfig())
    val config = _config.asStateFlow()
    private val _themes = MutableStateFlow(listOf(CouchTheme()))
    val themes = _themes.asStateFlow()
    private val _themeId = MutableStateFlow("classic")
    val themeId = _themeId.asStateFlow()
    val weather = WeatherRepository(app)
    val speed = SpeedRepository()
    val network = NetworkStatus(app)
    private val preferences = app.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val _speedEnabled = MutableStateFlow(preferences.getBoolean("speed", true))
    val speedEnabled = _speedEnabled.asStateFlow()
    fun setSpeedEnabled(enabled: Boolean) {
        _speedEnabled.value = enabled
        preferences.edit().putBoolean("speed", enabled).apply()
    }
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
        viewModelScope.launch {
            _themes.value = withContext(Dispatchers.IO) { ThemeAssets.load(context) }
            _themeId.value = preferences.getString("theme", "classic") ?: "classic"
        }
        viewModelScope.launch { _config.value = withContext(Dispatchers.IO) { LocalConfig.read(context) } }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { AppCatalog(context).load() }
            _apps.value = apps
            _loaded.value = true
            _recent.value = withContext(Dispatchers.IO) { recents.recent(apps.map { it.packageName }.toSet()) }
            _watchNext.value = withContext(Dispatchers.IO) { watchNextSource.load() }
        }
    }
}
