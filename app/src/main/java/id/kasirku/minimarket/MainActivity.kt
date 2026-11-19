package id.kasirku.minimarket

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID

private const val ADMIN_URL = "https://seamartstore.id"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var printerSocket: BluetoothSocket? = null
    @Volatile private var printerOutput: OutputStream? = null
    @Volatile private var printerName: String = ""

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun pairedPrinters(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().filter {
                val name = it.name.orEmpty().uppercase()
                name.contains("RONGTA") || name.contains("RPP02") || name.contains("RP-P")
            }
        } catch (_: SecurityException) { emptyList() }
    }

    private fun connectPrinter(): String {
        if (!hasBluetoothPermission()) return "Izin Bluetooth belum diberikan. Izinkan akses Bluetooth lalu coba lagi."
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth tidak tersedia di perangkat ini."
        if (!adapter.isEnabled) return "Aktifkan Bluetooth HP terlebih dahulu."
        val device = pairedPrinters().firstOrNull()
            ?: return "Printer RONGTA belum ditemukan. Pasangkan RONGTA RPP02N melalui Pengaturan Bluetooth Android terlebih dahulu."
        return try {
            printerSocket?.close()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            socket.connect()
            printerSocket = socket
            printerOutput = socket.outputStream
            printerName = device.name ?: "RONGTA"
            "Terhubung ke $printerName"
        } catch (e: Exception) {
            printerSocket = null
            printerOutput = null
            "Gagal menghubungkan printer: ${e.message ?: "periksa printer dan pairing Bluetooth"}"
        }
    }

    private fun printReceiptText(text: String): String {
        if (!hasBluetoothPermission()) return "Izin Bluetooth belum diberikan."
        return try {
            if (printerSocket?.isConnected != true || printerOutput == null) {
                val status = connectPrinter()
                if (!status.startsWith("Terhubung")) return status
            }
            val output = printerOutput ?: return "Printer belum terhubung."
            val clean = text.replace("\r", "").replace(Regex("[\\t ]+\\n"), "\n").trim()
            val wrapped = clean.lines().flatMap { line ->
                if (line.length <= 32) listOf(line) else line.chunked(32)
            }.joinToString("\n")
            val bytes = wrapped.toByteArray(Charsets.UTF_8)
            output.write(byteArrayOf(0x1B, 0x40))
            output.write(bytes)
            output.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x00))
            output.flush()
            "Struk dikirim ke $printerName"
        } catch (e: Exception) {
            printerSocket = null
            printerOutput = null
            "Cetak gagal: ${e.message ?: "koneksi printer terputus"}"
        }
    }

    inner class SeaMartPrinterBridge {
        @JavascriptInterface fun printerStatus(): String =
            if (printerSocket?.isConnected == true) "Terhubung ke $printerName" else "Printer belum terhubung"

        @JavascriptInterface fun connectPrinter(): String = this@MainActivity.connectPrinter()

        @JavascriptInterface fun printReceiptText(text: String): String =
            this@MainActivity.printReceiptText(text)

        // Dipanggil oleh halaman struk yang dibuka dari tombol CETAK STRUK.
        @JavascriptInterface fun printReceiptHtml(html: String): String {
            val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            return this@MainActivity.printReceiptText(plain)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissionIfNeeded()
        setContent {
            var webView by remember { mutableStateOf<WebView?>(null) }
            var chooserLauncher by remember { mutableStateOf<((Array<Uri>?) -> Unit)?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                chooserLauncher?.invoke(if (uri != null) arrayOf(uri) else null)
                chooserLauncher = null
            }

            BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

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
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        addJavascriptInterface(SeaMartPrinterBridge(), "SeaMartNativePrinter")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                // Tangkap jendela preview struk dari halaman web dan kirim teksnya ke printer Bluetooth.
                                view.evaluateJavascript(
                                    """
                                    (function(){
                                      if(window.__seaMartBtPrintHook)return;
                                      window.__seaMartBtPrintHook=true;
                                      const originalOpen=window.open.bind(window);
                                      window.open=function(){
                                        const buffer={html:''};
                                        const doc={
                                          open:function(){buffer.html='';},
                                          write:function(value){buffer.html+=String(value);},
                                          close:function(){
                                            const source=buffer.html;
                                            if(/struk|receipt|total/i.test(source) && window.SeaMartNativePrinter){
                                              const parsed=new DOMParser().parseFromString(source,'text/html');
                                              const text=(parsed.body?parsed.body.innerText:parsed.documentElement.innerText)||'';
                                              const result=window.SeaMartNativePrinter.printReceiptText(text);
                                              if(typeof window.toast==='function')window.toast(result);
                                              else alert(result);
                                            } else {
                                              const w=originalOpen.apply(window,[]);
                                              if(w){w.document.open();w.document.write(source);w.document.close();}
                                            }
                                          }
                                        };
                                        return {document:doc,focus:function(){},print:function(){}};
                                      };
                                    })();
                                    """.trimIndent(), null
                                )
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
                        if (savedInstanceState == null) loadUrl(ADMIN_URL)
                    }
                },
                update = { webView = it }
            )
        }
    }

    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null)
        uploadCallback = null
        try { printerSocket?.close() } catch (_: Exception) { }
        printerSocket = null
        printerOutput = null
        super.onDestroy()
    }
}
