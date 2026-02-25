package com.wifiauto

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var listViewRedes: ListView
    private lateinit var btnEscanear: Button
    private lateinit var btnSeleccionarArchivo: Button
    private lateinit var tvArchivoSeleccionado: TextView
    private lateinit var tvEstado: TextView

    private var redesDisponibles = mutableListOf<ScanResult>()
    private var contraseñas = mutableListOf<String>()
    private var archivoTxt: File? = null

    private val PERMISOS_REQUEST = 100
    private val OVERLAY_REQUEST = 101

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) mostrarRedes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        listViewRedes = findViewById(R.id.listViewRedes)
        btnEscanear = findViewById(R.id.btnEscanear)
        btnSeleccionarArchivo = findViewById(R.id.btnSeleccionarArchivo)
        tvArchivoSeleccionado = findViewById(R.id.tvArchivoSeleccionado)
        tvEstado = findViewById(R.id.tvEstado)

        pedirPermisos()
        pedirPermisoOverlay()

        btnEscanear.setOnClickListener { escanearRedes() }

        btnSeleccionarArchivo.setOnClickListener { seleccionarArchivo() }

        listViewRedes.setOnItemClickListener { _, _, position, _ ->
            val redSeleccionada = redesDisponibles[position]
            if (contraseñas.isEmpty()) {
                tvEstado.text = "⚠️ Primero selecciona el archivo de contraseñas"
            } else {
                tvEstado.text = "🔄 Intentando conectar a: ${redSeleccionada.SSID}"
                val service = Intent(this, WiFiService::class.java).apply {
                    putExtra("ssid", redSeleccionada.SSID)
                    putStringArrayListExtra("passwords", ArrayList(contraseñas))
                }
                startService(service)
            }
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }

    private fun pedirPermisos() {
        val permisos = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permisos.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permisos.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_DOCUMENTS)
                != PackageManager.PERMISSION_GRANTED) {
                permisos.add(Manifest.permission.READ_MEDIA_DOCUMENTS)
            }
        }
        if (permisos.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permisos.toTypedArray(), PERMISOS_REQUEST)
        }
    }

    private fun pedirPermisoOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_REQUEST)
            }
        }
    }

    private fun escanearRedes() {
        if (!wifiManager.isWifiEnabled) {
            tvEstado.text = "⚠️ El WiFi está desactivado. Actívalo primero."
            return
        }
        tvEstado.text = "🔍 Escaneando redes..."
        wifiManager.startScan()
    }

    private fun mostrarRedes() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        redesDisponibles = wifiManager.scanResults
            .filter { it.SSID.isNotEmpty() }
            .sortedByDescending { it.level }
            .toMutableList()

        val incorrectasPrefs = getSharedPreferences(WiFiService.PREFS_NAME, Context.MODE_PRIVATE)
        val nombres = redesDisponibles.map {
            val jsonStr = incorrectasPrefs.getString(it.SSID, null)
            val cantIncorrectas = if (jsonStr != null) {
                try { org.json.JSONArray(jsonStr).length() } catch (e: Exception) { 0 }
            } else 0
            val historial = if (cantIncorrectas > 0) "  ⚡$cantIncorrectas ya probadas" else ""
            "📶 ${it.SSID}  (${calcularSenal(it.level)})$historial"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, nombres)
        listViewRedes.adapter = adapter
        tvEstado.text = "✅ ${redesDisponibles.size} redes encontradas. Toca una para conectar."
    }

    private fun calcularSenal(level: Int): String {
        return when {
            level >= -50 -> "🟢 Excelente"
            level >= -60 -> "🟡 Buena"
            level >= -70 -> "🟠 Regular"
            else -> "🔴 Débil"
        }
    }

    private fun seleccionarArchivo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Selecciona archivo .txt"), 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val texto = inputStream?.bufferedReader()?.readText() ?: ""
                    inputStream?.close()

                    contraseñas = texto.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableList()

                    tvArchivoSeleccionado.text = "📄 Archivo cargado: ${contraseñas.size} contraseñas"
                    tvEstado.text = "✅ Contraseñas cargadas correctamente"
                } catch (e: Exception) {
                    tvEstado.text = "❌ Error al leer el archivo: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
    }
}
