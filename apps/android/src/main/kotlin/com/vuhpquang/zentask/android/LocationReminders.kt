package com.vuhpquang.zentask.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.vuhpquang.zentask.model.Place
import com.vuhpquang.zentask.tasks.LocationPlanner

/**
 * Location reminders on the phone. Two sources say which saved places the phone is at: geofences (GPS, Play
 * Services) and the connected Wi‑Fi (a place's Wi‑Fi names). Both update one "inside" set kept in prefs, and only a
 * change of that set notifies (so GPS + Wi‑Fi at the same place remind once).
 */
object LocationReminders {
    private const val TAG = "ZenTask"
    private const val PREFS = "zentask.location"
    private const val INSIDE_GPS = "insideGps"
    private const val INSIDE_WIFI = "insideWifi"

    fun hasForeground(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun geofenceIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 41, Intent(c, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun wifiIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 42, Intent(c, WifiChangeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Re-registers geofences for the places open tasks wait on, and the Wi‑Fi watch. Safe to call often. */
    @SuppressLint("MissingPermission")
    fun sync(c: Context) {
        val zen = (c.applicationContext as ZenTaskApplication).zen
        val watches = zen.core.locationWatches()
        val client = LocationServices.getGeofencingClient(c)
        client.removeGeofences(geofenceIntent(c))
        val gps = watches.filter { it.place.latitude != 0.0 || it.place.longitude != 0.0 }
        if (gps.isNotEmpty() && hasForeground(c) && hasBackground(c)) {
            val fences = gps.map { w ->
                Geofence.Builder().setRequestId(w.place.id)
                    .setCircularRegion(w.place.latitude, w.place.longitude, w.place.radius.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            // Initial trigger off: already being somewhere when the task is made doesn't "arrive" there.
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            client.addGeofences(req, geofenceIntent(c)).addOnFailureListener { Log.w(TAG, "Geofences not added: ${it.message}") }
        }
        val cm = c.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.unregisterNetworkCallback(wifiIntent(c)) }
        if (watches.any { it.place.wifi.isNotEmpty() }) {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            runCatching { cm.registerNetworkCallback(request, wifiIntent(c)) }.onFailure { Log.w(TAG, "Wi‑Fi watch failed: ${it.message}") }
            watchInProcess(c)
        }
        // Record where the Wi‑Fi says we are without notifying: being there already isn't "arriving".
        checkWifi(c, notify = false)
    }

    /** The SSID the phone is on (needs location permission), or null. */
    @Suppress("DEPRECATION")
    fun currentSsid(c: Context): String? {
        val info: WifiInfo? = c.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        return info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != WifiManager.UNKNOWN_SSID }
    }

    /** [ssid]: from the network callback when known (more reliable than asking WifiManager right then). */
    fun checkWifi(c: Context, notify: Boolean = true, ssid: String? = currentSsid(c)) {
        val zen = (c.applicationContext as ZenTaskApplication).zen
        val place = LocationPlanner.placeForWifi(zen.core.placeActions.list(), ssid)
        update(c, INSIDE_WIFI, setOfNotNull(place?.id), notify)
    }

    fun onGeofence(c: Context, entered: List<String>, exited: List<String>) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // GPS says we left: drop the place from the Wi‑Fi set too (a lost Wi‑Fi isn't always reported in the background).
        if (exited.isNotEmpty()) prefs.edit().putStringSet(INSIDE_WIFI, prefs.getStringSet(INSIDE_WIFI, emptySet()).orEmpty() - exited.toSet()).commit()
        val now = prefs.getStringSet(INSIDE_GPS, emptySet()).orEmpty() + entered - exited.toSet()
        update(c, INSIDE_GPS, now)
    }

    private var processCallback: ConnectivityManager.NetworkCallback? = null

    /** While the app process runs, also hear Wi‑Fi being lost (the PendingIntent watch only reports new networks). */
    private fun watchInProcess(c: Context) {
        if (processCallback != null) return
        val app = c.applicationContext
        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
                if (ssid != null) checkWifi(app, ssid = ssid)
            }
            override fun onLost(network: android.net.Network) = checkWifi(app, ssid = null)
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, cb); processCallback = cb }
    }

    /** Stores one source's inside-set; notifies for the change of the union of both sources. */
    @Synchronized
    private fun update(c: Context, key: String, value: Set<String>, notify: Boolean = true) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun union() = prefs.getStringSet(INSIDE_GPS, emptySet()).orEmpty() + prefs.getStringSet(INSIDE_WIFI, emptySet()).orEmpty()
        val before = union()
        prefs.edit().putStringSet(key, value).commit()
        val after = union()
        if (!notify) return
        val zen = (c.applicationContext as ZenTaskApplication).zen
        for ((placeId, trigger) in LocationPlanner.changes(before, after)) {
            val (tasks, text) = zen.core.locationReminder(placeId, trigger) ?: continue
            notify(c, placeId + trigger, text.first, text.second, tasks.singleOrNull()?.id)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(c: Context, key: String, title: String, body: String, taskId: String?) {
        Reminders.createChannel(c)
        val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { taskId?.let { putExtra(MainActivity.EXTRA_OPEN_TASK, it) } }
        val n = NotificationCompat.Builder(c, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(("place:$key").hashCode(), n) }
    }

    fun triggerTitle(trigger: String?) = if (trigger == Place.LEAVE) "Leaving" else "At"
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> LocationReminders.onGeofence(context, ids, emptyList())
            Geofence.GEOFENCE_TRANSITION_EXIT -> LocationReminders.onGeofence(context, emptyList(), ids)
        }
    }
}

class WifiChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LocationReminders.checkWifi(context)
}
