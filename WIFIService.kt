package com.wifiauto

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.*
import android.net.wifi.*
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

class WiFiService : Service() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var prefs: SharedPreferences
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ssid = ""
    private var todasLasContraseñas = listOf<String>()
    private var contraseñasPendientes = mutableListOf<String>()
    private var indiceActual = 0
    private var conectado = false

    companion object {
        const val TIMEOUT_MS = 3000L
        const val PREFS_NAME = "wifi_historial"
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        crearCanalNotificacion()
        startForeground(1, crearNotificacion("Iniciando conexión WiFi..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ssid = intent?.getStringExtra("ssid") ?: ""
        todasLasContraseñas = intent?.getStringArrayListExtra("passwords") ?: listOf()
        conectado = false

        if (ssid.isNotEmpty() && todasLasContraseñas.isNotEmpty()) {
            // Filtrar contraseñas ya probadas para esta red
            val incorrectasGuardadas = obtenerIncorrectas(ssid)
            contraseñasPendientes = todasLasContraseñas
                .filter { it !in incorrectasGuardadas }
                .toMutableList()

            indiceActual = 0

            if (contraseñasPendientes.isEmpty()) {
                mostrarOverlay("⚠️ Todas las contraseñas ya fueron probadas en $ssid\nAgrega nuevas al archivo .txt")
                actualizarNotificacion("⚠️ Sin contraseñas nuevas para $ssid")
                handler.postDelayed({ ocultarOverlay(); stopSelf() }, 4000)
            } else {
                val saltadas = todasLasContraseñas.size - contraseñasPendientes.size
                if (saltadas > 0) {
                    mostrarOverlay("⏩ Saltando $saltadas contraseñas ya probadas en $ssid")
                }
                handler.postDelayed({ intentarConectar() }, 800)
            }
        }

        return START_NOT_STICKY
    }

    private fun intentarConectar() {
        if (indiceActual >= contraseñasPendientes.size) {
            mostrarOverlay("❌ Ninguna contraseña funcionó para $ssid")
            actualizarNotificacion("❌ No se pudo conectar a $ssid")
            handler.postDelayed({ ocultarOverlay(); stopSelf() }, 4000)
            return
        }

        val contraseña = contraseñasPendientes[indiceActual]
        val numeroReal = todasLasContraseñas.indexOf(contraseña) + 1
        mostrarOverlay("🔄 Probando contraseña $numeroReal/${todasLasContraseñas.size} en $ssid")
        actualizarNotificacion("Probando contraseña $numeroReal/${todasLasContraseñas.size}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            conectarAndroid10(ssid, contraseña)
        } else {
            conectarAndroidLegacy(ssid, contraseña)
        }
    }

    private fun conectarAndroid10(ssid: String, password: String) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (!conectado) {
                    conectado = true
                    // Guardar contraseña correcta (limpiar historial de incorrectas para esta red)
                    limpiarHistorial(ssid)
                    handler.post {
                        val numeroReal = todasLasContraseñas.indexOf(password) + 1
                        mostrarOverlay("✅ Conectado a $ssid\nContraseña $numeroReal es la correcta ✅")
                        actualizarNotificacion("✅ Conectado a $ssid")
                        handler.postDelayed({ ocultarOverlay(); stopSelf() }, 4000)
                    }
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                if (!conectado) {
                    // Guardar esta contraseña como incorrecta
                    guardarIncorrecta(ssid, password)
                    handler.post {
                        indiceActual++
                        handler.postDelayed({ intentarConectar() }, TIMEOUT_MS)
                    }
                }
            }
        }

        connectivityManager.requestNetwork(request, callback, TIMEOUT_MS.toInt())
    }

    private fun conectarAndroidLegacy(ssid: String, password: String) {
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
        }

        val networkId = wifiManager.addNetwork(wifiConfig)
        wifiManager.disconnect()
        wifiManager.enableNetwork(networkId, true)
        wifiManager.reconnect()

        handler.postDelayed({
            val info = wifiManager.connectionInfo
            if (info.ssid == "\"$ssid\"" && info.networkId != -1) {
                conectado = true
                limpiarHistorial(ssid)
                val numeroReal = todasLasContraseñas.indexOf(password) + 1
                mostrarOverlay("✅ Conectado a $ssid\nContraseña $numeroReal es la correcta ✅")
                actualizarNotificacion("✅ Conectado a $ssid")
                handler.postDelayed({ ocultarOverlay(); stopSelf() }, 4000)
            } else {
                guardarIncorrecta(ssid, password)
                indiceActual++
                intentarConectar()
            }
        }, TIMEOUT_MS)
    }

    // ── Historial por red ──────────────────────────────────────────────

    private fun obtenerIncorrectas(ssid: String): Set<String> {
        val json = prefs.getString(ssid, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun guardarIncorrecta(ssid: String, password: String) {
        val actuales = obtenerIncorrectas(ssid).toMutableSet()
        actuales.add(password)
        val arr = JSONArray(actuales.toList())
        prefs.edit().putString(ssid, arr.toString()).apply()
    }

    private fun limpiarHistorial(ssid: String) {
        prefs.edit().remove(ssid).apply()
    }

    // ── Overlay ────────────────────────────────────────────────────────

    private fun mostrarOverlay(mensaje: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        handler.post {
            ocultarOverlay()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            overlayView?.findViewById<TextView>(R.id.tvOverlayMensaje)?.text = mensaje

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100
            }
            windowManager?.addView(overlayView, params)
        }
    }

    private fun ocultarOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    // ── Notificación ───────────────────────────────────────────────────

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                "wifi_canal", "WiFi Auto Connect", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun crearNotificacion(texto: String): Notification {
        return NotificationCompat.Builder(this, "wifi_canal")
            .setContentTitle("WiFi Auto Connect")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        getSystemService(NotificationManager::class.java).notify(1, crearNotificacion(texto))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ocultarOverlay()
    }
}
