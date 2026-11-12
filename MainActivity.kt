package com.example.rymclient

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.rymclient.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** true, пока мы сами переключаем пункт навигации (чтобы не грузить страницу повторно). */
    private var syncingNav = false

    private val textZooms = intArrayOf(100, 115, 130, 85)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val night = isNightMode()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        setupToolbar()
        setupBottomNav()
        setupWebView(night)

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        // Тянуть для обновления можно только когда страница прокручена в самый верх
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> binding.webView.scrollY > 0 }

        binding.retryButton.setOnClickListener {
            hideError()
            if (binding.webView.url != null) binding.webView.reload() else loadUrl(Config.HOME)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    hideError()
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val restored = savedInstanceState != null &&
            binding.webView.restoreState(savedInstanceState) != null
        if (!restored) {
            loadUrl(internalUrlFromIntent(intent) ?: Config.HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        internalUrlFromIntent(intent)?.let { loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        binding.swipeRefresh.removeAllViews()
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- toolbar

    private fun setupToolbar() {
        val toolbar = binding.toolbar
        toolbar.inflateMenu(R.menu.toolbar)

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val text = query?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    loadUrl(Config.searchUrl(text))
                    searchView.clearFocus()
                    searchItem.collapseActionView()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> binding.webView.reload()
                R.id.action_share -> shareCurrentPage()
                R.id.action_browser -> binding.webView.url?.let { openExternal(Uri.parse(it)) }
                R.id.action_text_size -> cycleTextSize()
                R.id.action_clear -> confirmClearSiteData()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun updateTitle(title: String?) {
        binding.toolbar.title = title
            ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            ?: getString(R.string.app_name)
    }

    // ------------------------------------------------------------ bottom nav

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!syncingNav) Config.sectionUrl(item.itemId)?.let { loadUrl(it) }
            true
        }
        // Повторное нажатие на активную вкладку возвращает в начало раздела
        binding.bottomNav.setOnItemReselectedListener { item ->
            if (!syncingNav) Config.sectionUrl(item.itemId)?.let { loadUrl(it) }
        }
    }

    private fun syncNav(url: String?) {
        val id = Config.navIdFor(url) ?: return
        if (binding.bottomNav.selectedItemId == id) return
        syncingNav = true
        binding.bottomNav.selectedItemId = id
        syncingNav = false
    }

    // --------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(night: Boolean) {
        val webView = binding.webView

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, 100)
        }

        // Тёмная тема страниц следует за системной
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, night)
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hideError()
                binding.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progress.isVisible = false
                binding.swipeRefresh.isRefreshing = false
                syncNav(url)
                updateTitle(view.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                syncNav(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.isVisible = newProgress < 100
            }
        }
    }

    private fun loadUrl(url: String) {
        hideError()
        binding.webView.loadUrl(url)
    }

    private fun showError() {
        binding.errorView.isVisible = true
        binding.progress.isVisible = false
        binding.swipeRefresh.isRefreshing = false
    }

    private fun hideError() {
        binding.errorView.isVisible = false
    }

    // ----------------------------------------------------------------- utils

    private fun isInternal(uri: Uri): Boolean {
        val scheme = uri.scheme
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host ?: return false
        return host == Config.HOST || host.endsWith(".${Config.HOST}")
    }

    private fun internalUrlFromIntent(intent: Intent?): String? =
        intent?.data?.takeIf { isInternal(it) }?.toString()

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPage() {
        val url = binding.webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun cycleTextSize() {
        val current = binding.webView.settings.textZoom
        val next = textZooms[(textZooms.indexOf(current) + 1) % textZooms.size]
        binding.webView.settings.textZoom = next
        prefs.edit().putInt(KEY_TEXT_ZOOM, next).apply()
        Toast.makeText(this, getString(R.string.text_size_format, next), Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearSiteData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear_confirm) { _, _ -> clearSiteData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        loadUrl(Config.HOME)
        Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val KEY_TEXT_ZOOM = "text_zoom"
    }
}
