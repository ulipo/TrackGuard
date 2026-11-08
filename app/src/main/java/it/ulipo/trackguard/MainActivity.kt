package it.ulipo.trackguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

class MainActivity : Activity() {
    private lateinit var fileName: TextView
    private lateinit var threshold: EditText
    private lateinit var repeatInterval: Spinner
    private lateinit var status: TextView
    private var selectedUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("trackguard", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fileName = findViewById(R.id.fileName)
        threshold = findViewById(R.id.threshold)
        repeatInterval = findViewById(R.id.repeatInterval)
        val intervalValues = intArrayOf(30, 60, 120, 180, 300, 600)
        val intervalLabels = arrayOf("30 secondi", "1 minuto", "2 minuti", "3 minuti", "5 minuti", "10 minuti")
        repeatInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedInterval = prefs.getInt(KEY_REPEAT_INTERVAL, 120)
        repeatInterval.setSelection(intervalValues.indexOf(savedInterval).takeIf { it >= 0 } ?: 2)
        status = findViewById(R.id.status)
        selectedUri = prefs.getString(KEY_URI, null)?.let(Uri::parse)
        threshold.setText(prefs.getInt(KEY_THRESHOLD, 30).toString())
        refreshUi()

        findViewById<MaterialButton>(R.id.selectFile).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
            }
            startActivityForResult(intent, REQUEST_GPX)
        }
        findViewById<MaterialButton>(R.id.toggleTracking).setOnClickListener {
            if (prefs.getBoolean(KEY_ACTIVE, false)) stopMonitoring() else startMonitoring()
        }
    }

    override fun onResume() { super.onResume(); refreshUi() }

    @Deprecated("Using the platform document picker keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GPX && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            try {
                val track = GpxTrack.read(this, uri)
                selectedUri = uri
                prefs.edit().putString(KEY_URI, uri.toString()).apply()
                val name = displayName(uri)
                fileName.text = "$name · ${track.segments.sumOf { it.size }} punti"
                Toast.makeText(this, "Traccia caricata", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "GPX non valido: ${e.message ?: "errore di lettura"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoring() {
        val uri = selectedUri ?: run {
            Toast.makeText(this, "Carica prima una traccia GPX", Toast.LENGTH_SHORT).show(); return
        }
        val meters = threshold.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 30
        threshold.setText(meters.toString())
        val intervalValues = intArrayOf(30, 60, 120, 180, 300, 600)
        val intervalSeconds = intervalValues.getOrElse(repeatInterval.selectedItemPosition) { 120 }
        prefs.edit().putInt(KEY_THRESHOLD, meters).putInt(KEY_REPEAT_INTERVAL, intervalSeconds)
            .putString(KEY_URI, uri.toString()).apply()
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_URI, uri.toString())
            putExtra(TrackingService.EXTRA_THRESHOLD, meters)
            putExtra(TrackingService.EXTRA_REPEAT_INTERVAL, intervalSeconds)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
            refreshUi()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile avviare il GPS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, TrackingService::class.java))
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        refreshUi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startMonitoring()
            else Toast.makeText(this, "Servono posizione precisa e notifiche per monitorare la traccia.", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Traccia GPX"
    }

    private fun refreshUi() {
        val uri = selectedUri
        if (uri != null) {
            fileName.text = displayName(uri)
        }
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        status.text = if (active) "MONITORAGGIO ATTIVO · SOGLIA ${prefs.getInt(KEY_THRESHOLD, 30)} m" else "MONITORAGGIO NON ATTIVO"
        findViewById<MaterialButton>(R.id.toggleTracking)?.let { button ->
            button.text = if (active) "■" else "▶"
            button.contentDescription = if (active) "Ferma monitoraggio" else "Avvia monitoraggio"
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(if (active) R.color.nord_red else R.color.nord_primary)
            ))
        }
        findViewById<TextView>(R.id.toggleHint)?.text = if (active) "Tocca per fermare" else "Tocca per avviare"
    }

    companion object {
        const val KEY_URI = "gpx_uri"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_ACTIVE = "active"
        const val KEY_REPEAT_INTERVAL = "repeat_interval_seconds"
        private const val REQUEST_GPX = 101
        private const val REQUEST_PERMISSIONS = 102
    }
}
