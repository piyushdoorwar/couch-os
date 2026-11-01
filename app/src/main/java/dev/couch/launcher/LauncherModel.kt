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
    private val _config = MutableStateFlow(LocalConfig())
    val config = _config.asStateFlow()
    private val _themes = MutableStateFlow(listOf(CouchTheme()))
    val themes = _themes.asStateFlow()
    private val _themeId = MutableStateFlow("midnight-mint")
    val themeId = _themeId.asStateFlow()
    val weather = WeatherRepository(app)
    private val preferences = app.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val _homeRequest = MutableStateFlow(0)
    val homeRequest = _homeRequest.asStateFlow()
    fun showHome() { _homeRequest.value += 1 }
    fun selectTheme(id: String) {
        if (_themes.value.any { it.id == id }) {
            _themeId.value = id
            preferences.edit().putString("theme", id).apply()
        }
    }
    fun refresh() = viewModelScope.launch {
        val context = getApplication<Application>()
        _themes.value = withContext(Dispatchers.IO) { ThemeAssets.load(context) }
        _themeId.value = preferences.getString("theme", "midnight-mint") ?: "midnight-mint"
        _config.value = withContext(Dispatchers.IO) { LocalConfig.read(context) }
        _apps.value = withContext(Dispatchers.IO) { AppCatalog(context).load() }
    }
}
