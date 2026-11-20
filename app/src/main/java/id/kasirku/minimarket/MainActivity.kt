package id.kasirku.minimarket

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val ADMIN_URL = "https://seamartstore.id"

class MainActivity : ComponentActivity() {
    private var uploadCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var webView by remember { mutableStateOf<WebView?>(null) }
            var isLoading by remember { mutableStateOf(true) }
            var loadError by remember { mutableStateOf<String?>(null) }
            var chooserLauncher by remember { mutableStateOf<((Array<Uri>?) -> Unit)?>(null) }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                chooserLauncher?.invoke(if (uri != null) arrayOf(uri) else null)
                chooserLauncher = null
            }

            BackHandler(enabled = webView?.canGoBack() == true) {
                webView?.goBack()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            settings.loadsImagesAutomatically = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    isLoading = true
                                    loadError = null
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    isLoading = false
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    if (request.isForMainFrame) {
                                        isLoading = false
                                        loadError = "Halaman gagal dimuat: ${error.description} (kode ${error.errorCode}). Periksa koneksi internet lalu coba lagi."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: android.webkit.WebResourceResponse
                                ) {
                                    if (request.isForMainFrame) {
                                        isLoading = false
                                        loadError = "Server mengirim HTTP ${errorResponse.statusCode}. Coba muat ulang beberapa saat lagi."
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme ?: return true
                                    return scheme != "https" && scheme != "http"
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    view: WebView,
                                    filePathCallback: ValueCallback<Array<Uri>>,
                                    fileChooserParams: FileChooserParams
                                ): Boolean {
                                    uploadCallback?.onReceiveValue(null)
                                    uploadCallback = filePathCallback
                                    chooserLauncher = { result ->
                                        uploadCallback?.onReceiveValue(result)
                                        uploadCallback = null
                                    }
                                    picker.launch("*/*")
                                    return true
                                }
                            }

                            webView = this
                            if (savedInstanceState == null) {
                                loadUrl(ADMIN_URL)
                            } else {
                                loadUrl(ADMIN_URL)
                            }
                        }
                    },
                    update = { webView = it }
                )

                if (isLoading && loadError == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Memuat SeaMart POS…", color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                loadError?.let { message ->
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("SeaMart belum dapat dibuka", style = MaterialTheme.typography.titleLarge)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            loadError = null
                            isLoading = true
                            webView?.loadUrl(ADMIN_URL)
                        }) {
                            Text("Coba lagi")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null)
        uploadCallback = null
        super.onDestroy()
    }
}
