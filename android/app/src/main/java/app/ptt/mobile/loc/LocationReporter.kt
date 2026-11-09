package app.ptt.mobile.loc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class LocationReporter(
    private val ctx: Context,
    private val onFix: (lat: Double, lng: Double, acc: Float?, battery: Double?, charging: Boolean?) -> Unit,
) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var last: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            last = location
            emit(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val periodic = object : Runnable {
        override fun run() {
            if (!running) return
            last?.let { emit(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, 10_000L, 5f, listener, Looper.getMainLooper())
            } catch (_: Exception) {}
            try {
                val cached = lm.getLastKnownLocation(p)
                if (cached != null && (last == null || cached.time > (last?.time ?: 0))) last = cached
            } catch (_: Exception) {}
        }
        last?.let { emit(it) }
        handler.postDelayed(periodic, 30_000)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(periodic)
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        last = null
    }

    fun battery(): Pair<Double?, Boolean?> {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level.toDouble() / scale) else null
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        } catch (_: Exception) {
            null to null
        }
    }

    private fun emit(loc: Location) {
        val (b, c) = battery()
        onFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, b, c)
    }
}
