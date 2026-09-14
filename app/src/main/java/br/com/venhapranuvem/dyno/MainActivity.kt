package br.com.venhapranuvem.dyno

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity

/**
 * App do Dinamômetro EV.
 * Mantém a conexão com o ESP32 estável:
 *  - Mantém a tela ligada (timers do WebView não são suspensos)
 *  - WifiLock em alta performance (o rádio Wi-Fi não "dorme")
 *  - Prende o tráfego do app na rede do ESP32, mesmo ela NÃO tendo internet
 *    (senão o Android troca para os dados móveis e perde o 192.168.4.1)
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantém a tela ligada enquanto o app está aberto
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.keepScreenOn = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")

        travarWifi()
        prenderNaRedeDoEsp32()
    }

    /** Mantém o rádio Wi-Fi ativo em alta performance. */
    private fun travarWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dyno:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Faz o tráfego do app sair pela rede Wi-Fi mesmo quando ela não tem
     * internet (o caso do Access Point do ESP32). Sem isto, o Android manda
     * as requisições para os dados móveis e a conexão "cai".
     */
    private fun prenderNaRedeDoEsp32() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // remove a exigência de "ter internet" para casar com o AP do ESP32
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A partir de agora, todo o tráfego do app vai por esta rede Wi-Fi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(null)
                }
            }
        }
        try {
            cm.requestNetwork(request, netCallback as ConnectivityManager.NetworkCallback)
        } catch (e: Exception) {
            // Se falhar (permissão/versão), o app segue funcionando com a tela ligada
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null)
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) { }
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }
}
