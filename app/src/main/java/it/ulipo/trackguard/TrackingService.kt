package it.ulipo.trackguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.PowerManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

class TrackingService : Service() {
    private lateinit var locationManager: LocationManager
    private lateinit var manager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationListener: LocationListener? = null
    private var track: GpxTrack? = null
    private var threshold = 30.0
    private var repeatIntervalMs = 2 * 60 * 1000L
    private var outside = false
    private var lastAlertAt = 0L
    private var lastAlertDistance = 0.0
    private var filteredCourse: Float? = null
    private var lastCourseAt = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("trackguard", MODE_PRIVATE)
        val uriString = intent?.getStringExtra(EXTRA_URI) ?: prefs.getString(MainActivity.KEY_URI, null)
        threshold = (intent?.getIntExtra(EXTRA_THRESHOLD, prefs.getInt(MainActivity.KEY_THRESHOLD, 30))
            ?: prefs.getInt(MainActivity.KEY_THRESHOLD, 30)).coerceIn(10, 300).toDouble()
        val intervalSeconds = (intent?.getIntExtra(EXTRA_REPEAT_INTERVAL,
            prefs.getInt(MainActivity.KEY_REPEAT_INTERVAL, 120))
            ?: prefs.getInt(MainActivity.KEY_REPEAT_INTERVAL, 120)).coerceIn(30, 600)
        repeatIntervalMs = intervalSeconds * 1000L

        startForeground(NOTIFICATION_STATUS, statusNotification("Carico la traccia GPX…"))
        if (uriString.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            track = GpxTrack.read(this, android.net.Uri.parse(uriString))
            prefs.edit().putString(MainActivity.KEY_URI, uriString)
                .putInt(MainActivity.KEY_THRESHOLD, threshold.roundToInt())
                .putInt(MainActivity.KEY_REPEAT_INTERVAL, (repeatIntervalMs / 1000L).toInt())
                .putBoolean(MainActivity.KEY_ACTIVE, true).apply()
            manager.notify(NOTIFICATION_STATUS, statusNotification("Controllo GPS attivo"))
            acquireWakeLock()
            requestLocations()
        } catch (e: Exception) {
            Log.e(TAG, "Errore caricamento GPX", e)
            manager.notify(NOTIFICATION_STATUS, statusNotification("Errore nel file GPX"))
            stopSelf()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackGuard:Tracking").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun requestLocations() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { processLocation(location) }
        }
        locationListener = listener
        try {
            // GPS_PROVIDER usa il ricevitore GNSS del telefono e non richiede rete dati.
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.notify(NOTIFICATION_STATUS, statusNotification("Attiva la posizione GPS"))
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permesso posizione mancante", e)
            stopSelf()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "GPS non disponibile", e)
            manager.notify(NOTIFICATION_STATUS, statusNotification("GPS non disponibile"))
            stopSelf()
        }
    }

    private fun processLocation(location: Location) {
        // Ignora fix troppo imprecisi: altrimenti gli allarmi sarebbero fuorvianti.
        if (location.hasAccuracy() && location.accuracy > 50f) return
        val nearest = track?.nearestTo(GeoPoint(location.latitude, location.longitude)) ?: return
        updateCourse(location)
        val distance = nearest.distanceMeters
        if (!outside && distance > threshold) {
            outside = true
            sendOffRouteAlert(distance, nearest.bearingDegrees.toFloat(), true)
            return
        }
        if (outside) {
            val clearThreshold = (threshold * 0.65).coerceAtLeast(5.0)
            if (distance <= clearThreshold) {
                outside = false
                manager.cancel(NOTIFICATION_ALERT)
                lastAlertAt = 0L
                lastAlertDistance = 0.0
                manager.notify(NOTIFICATION_STATUS, statusNotification("Traccia ripresa · GPS attivo"))
            } else {
                val now = SystemClock.elapsedRealtime()
                val movedFurther = distance >= lastAlertDistance + 20.0
                val timeElapsed = now - lastAlertAt >= repeatIntervalMs
                if (movedFurther || timeElapsed) sendOffRouteAlert(distance, nearest.bearingDegrees.toFloat(), true)
            }
        }
    }

    private fun updateCourse(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (location.hasBearing() && location.speed >= 0.8f &&
            (!location.hasAccuracy() || location.accuracy <= 35f) &&
            (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 35f)) {
            val incoming = normalizeDegrees(location.bearing)
            val previous = filteredCourse
            filteredCourse = if (previous == null) incoming else normalizeDegrees(previous + signedAngle(incoming - previous) * 0.25f)
            lastCourseAt = now
        }
        if (now - lastCourseAt > COURSE_MAX_AGE_MS) filteredCourse = null
    }

    private fun sendOffRouteAlert(distance: Double, targetBearing: Float, alertAgain: Boolean) {
        val labels = directionLabels(targetBearing, filteredCourse)
        val meters = distance.roundToInt()
        val title = "FUORI TRACCIA - ${meters}m"
        val relativeLine = labels.relative?.let { "vai verso $it" }
        val absoluteLine = "${labels.absolute} ${labels.absoluteDegrees % 360}°"
        val notificationText = relativeLine ?: absoluteLine
        val big = listOfNotNull(relativeLine, absoluteLine).joinToString("\n")
        val notification = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(notificationText)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent())
            .setWhen(System.currentTimeMillis())
            .build()
        // Cancel + notify permette ai dispositivi indossabili di ricevere anche le ripetizioni.
        if (alertAgain) manager.cancel(NOTIFICATION_ALERT)
        manager.notify(NOTIFICATION_ALERT, notification)
        lastAlertAt = SystemClock.elapsedRealtime()
        lastAlertDistance = distance
        manager.notify(NOTIFICATION_STATUS, statusNotification("Fuori traccia · ${meters} m"))
    }

    private fun statusNotification(text: String): Notification = Notification.Builder(this, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("TRACCIA ATTIVA")
        .setContentText(text)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppPendingIntent())
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val statusChannel = NotificationChannel(CHANNEL_STATUS, "Monitoraggio traccia", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indica che il controllo GPS è attivo"
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(CHANNEL_ALERT, "Avvisi fuori traccia", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avvisi quando ci si allontana dal percorso GPX"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 180, 350)
                setShowBadge(true)
            }
            manager.createNotificationChannel(statusChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun stopTracking() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        manager.cancel(NOTIFICATION_ALERT)
        manager.cancel(NOTIFICATION_STATUS)
        getSharedPreferences("trackguard", MODE_PRIVATE).edit().putBoolean(MainActivity.KEY_ACTIVE, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationListener?.let { locationManager.removeUpdates(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (::manager.isInitialized) {
            manager.cancel(NOTIFICATION_ALERT)
            manager.cancel(NOTIFICATION_STATUS)
        }
        getSharedPreferences("trackguard", MODE_PRIVATE).edit().putBoolean(MainActivity.KEY_ACTIVE, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "it.ulipo.trackguard.START"
        const val ACTION_STOP = "it.ulipo.trackguard.STOP"
        const val EXTRA_URI = "gpx_uri"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_REPEAT_INTERVAL = "repeat_interval_seconds"
        private const val TAG = "TrackGuard"
        private const val CHANNEL_STATUS = "tracking_status"
        private const val CHANNEL_ALERT = "off_route_alerts"
        private const val NOTIFICATION_STATUS = 4101
        private const val NOTIFICATION_ALERT = 4102
        private const val COURSE_MAX_AGE_MS = 30 * 1000L
    }
}
