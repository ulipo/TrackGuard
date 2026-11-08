package it.ulipo.trackguard

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)
data class NearestPoint(val distanceMeters: Double, val bearingDegrees: Double)

class GpxTrack private constructor(val segments: List<List<GeoPoint>>) {
    fun nearestTo(point: GeoPoint): NearestPoint? {
        val earth = 6371000.0
        val cosLat = cos(Math.toRadians(point.lat)).coerceAtLeast(0.000001)
        var bestDistance = Double.POSITIVE_INFINITY
        var bestX = 0.0
        var bestY = 0.0

        for (segment in segments) {
            if (segment.size == 1) {
                val p = segment[0]
                val x = Math.toRadians(p.lon - point.lon) * earth * cosLat
                val y = Math.toRadians(p.lat - point.lat) * earth
                val d = hypot(x, y)
                if (d < bestDistance) { bestDistance = d; bestX = x; bestY = y }
                continue
            }
            for (i in 0 until segment.size - 1) {
                val a = segment[i]
                val b = segment[i + 1]
                val ax = Math.toRadians(a.lon - point.lon) * earth * cosLat
                val ay = Math.toRadians(a.lat - point.lat) * earth
                val bx = Math.toRadians(b.lon - point.lon) * earth * cosLat
                val by = Math.toRadians(b.lat - point.lat) * earth
                val vx = bx - ax
                val vy = by - ay
                val length2 = vx * vx + vy * vy
                val t = if (length2 == 0.0) 0.0 else ((-ax * vx - ay * vy) / length2).coerceIn(0.0, 1.0)
                val x = ax + t * vx
                val y = ay + t * vy
                val d = hypot(x, y)
                if (d < bestDistance) { bestDistance = d; bestX = x; bestY = y }
            }
        }
        if (!bestDistance.isFinite()) return null
        val bearing = (Math.toDegrees(atan2(bestX, bestY)) + 360.0) % 360.0
        return NearestPoint(bestDistance, bearing)
    }

    companion object {
        fun read(context: Context, uri: Uri): GpxTrack {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Impossibile aprire il file GPX" }
                return parse(input)
            }
        }

        private fun parse(input: InputStream): GpxTrack {
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            val segments = mutableListOf<MutableList<GeoPoint>>()
            var current: MutableList<GeoPoint>? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "trkseg" -> { current = mutableListOf(); segments.add(current) }
                        "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                if (current == null) { current = mutableListOf(); segments.add(current) }
                                current?.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name.lowercase()) {
                        "trkseg", "rte" -> current = null
                    }
                }
                event = parser.next()
            }
            val valid = segments.filter { it.isNotEmpty() }
            require(valid.sumOf { it.size } >= 2) { "Il GPX non contiene almeno due punti di traccia o percorso." }
            return GpxTrack(valid)
        }
    }
}

fun normalizeDegrees(value: Float): Float {
    var v = value % 360f
    if (v < 0f) v += 360f
    return v
}

fun signedAngle(degrees: Float): Float {
    var v = (degrees + 540f) % 360f - 180f
    if (v == -180f) v = 180f
    return v
}

fun cardinal(degrees: Float): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return names[(floor((normalizeDegrees(degrees) + 22.5f) / 45f).toInt()) % 8]
}

data class DirectionLabels(val relative: String?, val arrow: String, val absolute: String, val absoluteDegrees: Int)

fun directionLabels(targetBearing: Float, course: Float?): DirectionLabels {
    val abs = normalizeDegrees(targetBearing)
    val absolute = cardinal(abs)
    if (course == null) return DirectionLabels(null, "", absolute, abs.roundToInt())
    val delta = signedAngle(abs - course)
    val relative: String
    val arrow: String
    when {
        delta >= -22.5f && delta < 22.5f -> { relative = "AVANTI"; arrow = "↑" }
        delta >= 22.5f && delta < 67.5f -> { relative = "DESTRA"; arrow = "↗" }
        delta >= 67.5f && delta < 112.5f -> { relative = "DESTRA"; arrow = "→" }
        delta >= 112.5f && delta < 157.5f -> { relative = "DIETRO DESTRA"; arrow = "↘" }
        delta >= 157.5f || delta < -157.5f -> { relative = "DIETRO"; arrow = "↓" }
        delta >= -157.5f && delta < -112.5f -> { relative = "DIETRO SINISTRA"; arrow = "↙" }
        delta >= -112.5f && delta < -67.5f -> { relative = "SINISTRA"; arrow = "←" }
        else -> { relative = "SINISTRA"; arrow = "↖" }
    }
    return DirectionLabels(relative, arrow, absolute, abs.roundToInt())
}
