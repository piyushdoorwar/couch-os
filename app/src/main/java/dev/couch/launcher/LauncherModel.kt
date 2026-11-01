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
    fun refresh() = viewModelScope.launch {
        val context = getApplication<Application>()
        _config.value = withContext(Dispatchers.IO) { LocalConfig.read(context) }
        _apps.value = withContext(Dispatchers.IO) { AppCatalog(context).load() }
    }
}
