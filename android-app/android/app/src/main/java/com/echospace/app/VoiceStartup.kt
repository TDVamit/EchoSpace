package com.echospace.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Optional location and a coarse portrait-orientation check for startup guidance. */
internal object VoiceStartup {
  fun get(context: Context, promise: Promise) {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    val result = JSONObject().put("phone_position", "unknown")
    val handler = Handler(Looper.getMainLooper())
    val delivered = AtomicBoolean(false)
    val listener = object : SensorEventListener {
      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
      override fun onSensorChanged(event: SensorEvent) {
        result.put("phone_position", if (abs(event.values[1]) > 7f && abs(event.values[2]) < 5.5f) "upright" else "not_upright")
        manager.unregisterListener(this)
        finish()
      }
      fun finish() {
        if (!delivered.compareAndSet(false, true)) return
        Thread {
          try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
              val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
              val location = lm.getProviders(true).mapNotNull { provider -> try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null } }
                .maxByOrNull { it.time }
              if (location != null && System.currentTimeMillis() - location.time < 30 * 60_000L && Geocoder.isPresent()) {
                @Suppress("DEPRECATION") val address = Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                val place = address?.locality ?: address?.subAdminArea ?: address?.adminArea
                if (!place.isNullOrBlank()) result.put("approximate_location", place)
              }
            }
          } catch (_: Exception) { /* Location is optional. */ }
          promise.resolve(result.toString())
        }.start()
      }
    }
    if (sensor == null || !manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) listener.finish()
    else handler.postDelayed({ manager.unregisterListener(listener); listener.finish() }, 1200)
  }
}
