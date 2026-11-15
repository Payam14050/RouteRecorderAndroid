package net.osmtracker.route

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import net.osmtracker.R
import kotlin.math.max
import kotlin.math.roundToInt

class RouteRecordingService : Service() {
    companion object {
        const val ACTION_START="net.osmtracker.route.START"; const val ACTION_STOP="net.osmtracker.route.STOP"
        const val ACTION_LOCATION="net.osmtracker.route.LOCATION"
        const val EXTRA_LAT="lat"; const val EXTRA_LON="lon"; const val EXTRA_ALT="alt"
        const val EXTRA_HAS_ALT="has_alt"; const val EXTRA_SPEED="speed"; const val EXTRA_DISTANCE="distance"
        const val EXTRA_ACCURACY="accuracy"; const val EXTRA_SATELLITES="satellites"
        private const val CHANNEL="route_recording"; private const val NOTIFICATION=731
        private const val MAX_ACCURACY=100f; private const val DISTANCE_ACCURACY=50f; private const val MAX_GAP=30_000L
    }
    private lateinit var store: RouteStore
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var manager: LocationManager
    private lateinit var callbackThread: HandlerThread
    private lateinit var callbackHandler: Handler
    private var tripId=-1L; private var previous: Location?=null; private var distance=0.0
    private var satellitesUsed=-1; private var usingFallback=false
    private var wakeLock: PowerManager.WakeLock?=null

    private val fusedCallback=object: LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { acceptLocation(it,"fused") } }
        override fun onLocationAvailability(a: LocationAvailability) {
            if(tripId>=0) store.recordEvent(tripId,"location_availability",a.isLocationAvailable.toString())
        }
    }
    private val gnssCallback=object: GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used=0
            for(i in 0 until status.satelliteCount) if(status.usedInFix(i)) used++
            satellitesUsed=used
            if(tripId>=0) store.recordGnss(tripId,status.satelliteCount,used)
        }
    }
    private val fallbackListener=object: LocationListener {
        override fun onLocationChanged(location: Location) { acceptLocation(location,"gps_fallback") }
        @Deprecated("Deprecated") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) = Unit
        override fun onProviderEnabled(provider:String) = Unit
        override fun onProviderDisabled(provider:String) {
            if(tripId>=0) store.recordEvent(tripId,"provider_disabled",provider)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store=RouteStore(this); fused=LocationServices.getFusedLocationProviderClient(this)
        manager=getSystemService(LOCATION_SERVICE) as LocationManager
        callbackThread=HandlerThread("RouteGpsCallbacks").also { it.start() }
        callbackHandler=Handler(callbackThread.looper); createChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP) { stopRecording(); return START_NOT_STICKY }
        if(intent?.action==ACTION_START || intent==null) {
            if(tripId<0) {
                tripId=if(intent==null) store.activeTripId() ?: store.startTrip() else store.startTrip()
                val saved=store.lastPoint(tripId)
                previous=saved?.let { Location("restored").apply {
                    latitude=it.lat; longitude=it.lon; time=it.time
                    if(it.accuracy>=0) accuracy=it.accuracy
                } }
                distance=store.trips().firstOrNull { it.id==tripId }?.distance ?: 0.0
                startForeground(NOTIFICATION,notification("در انتظار دریافت موقعیت دقیق GPS"))
                store.recordEvent(tripId,"service_started",if(intent==null) "recovered" else "user")
                acquireWakeLock(); requestLocation()
            }
        }
        return START_STICKY
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        if(!hasPermission()) { store.recordEvent(tripId,"permission_missing"); stopRecording(); return }
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,1_000L)
            .setMinUpdateIntervalMillis(500L).setMaxUpdateDelayMillis(0L).setWaitForAccurateLocation(true).build()
        try {
            fused.requestLocationUpdates(request,fusedCallback,callbackThread.looper).addOnFailureListener { e ->
                if(tripId>=0) store.recordEvent(tripId,"fused_request_failed",e.javaClass.simpleName)
                startGpsFallback()
            }
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N) {
                try { manager.registerGnssStatusCallback(gnssCallback,callbackHandler) }
                catch(e:Exception) { store.recordEvent(tripId,"gnss_callback_failed",e.javaClass.simpleName) }
            }
        } catch(e:SecurityException) {
            store.recordEvent(tripId,"location_security_exception",e.message); startGpsFallback()
        }
    }

    private fun startGpsFallback() {
        if(usingFallback || tripId<0) return
        usingFallback=true
        try {
            if(hasPermission()) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1_000L,0f,fallbackListener,callbackThread.looper)
                store.recordEvent(tripId,"gps_fallback_started")
            }
        } catch(e:Exception) { store.recordEvent(tripId,"gps_fallback_failed",e.javaClass.simpleName) }
    }

    private fun acceptLocation(location:Location,provider:String) {
        if(tripId<0 || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        if(!location.hasAccuracy() || location.accuracy>MAX_ACCURACY) {
            store.recordEvent(tripId,"fix_rejected_accuracy",if(location.hasAccuracy()) location.accuracy.toString() else "missing")
            return
        }
        val old=previous
        if(old!=null) {
            val gap=location.time-old.time
            if(gap in 1..MAX_GAP && location.accuracy<=DISTANCE_ACCURACY &&
                (!old.hasAccuracy() || old.accuracy<=DISTANCE_ACCURACY)) {
                val segment=old.distanceTo(location).toDouble()
                val speed=segment/max(gap/1000.0,0.001)
                val uncertainty=(old.accuracy+location.accuracy).toDouble()
                if(speed<=90.0 || segment<=uncertainty) distance+=segment
                else store.recordEvent(tripId,"implausible_jump","distance_m=$segment;gap_ms=$gap")
            } else if(gap>MAX_GAP) store.recordEvent(tripId,"location_gap","gap_ms=$gap")
        }
        previous=Location(location)
        val point=RoutePoint(location.latitude,location.longitude,
            if(location.hasAltitude()) location.altitude else Double.NaN,location.time,
            if(location.hasSpeed()) location.speed else 0f,location.accuracy,
            if(location.hasBearing()) location.bearing else -1f,satellitesUsed,provider)
        store.addPoint(tripId,point,distance)
        sendBroadcast(Intent(ACTION_LOCATION).setPackage(packageName).apply {
            putExtra(EXTRA_LAT,point.lat); putExtra(EXTRA_LON,point.lon)
            putExtra(EXTRA_ALT,if(point.ele.isFinite()) point.ele else 0.0)
            putExtra(EXTRA_HAS_ALT,point.ele.isFinite()); putExtra(EXTRA_SPEED,point.speed)
            putExtra(EXTRA_DISTANCE,distance); putExtra(EXTRA_ACCURACY,point.accuracy)
            putExtra(EXTRA_SATELLITES,satellitesUsed)
        })
        val kmh=(point.speed*3.6f).roundToInt()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION,
            notification("$kmh km/h • "+String.format(java.util.Locale.US,"%.2f km",distance/1000)))
    }

    private fun acquireWakeLock() {
        try {
            val pm=getSystemService(POWER_SERVICE) as PowerManager
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:RouteRecording").apply {
                setReferenceCounted(false); acquire(8*60*60*1000L)
            }
        } catch(e:Exception) { store.recordEvent(tripId,"wakelock_failed",e.javaClass.simpleName) }
    }

    private fun stopRecording() {
        if(tripId>=0) { store.recordEvent(tripId,"service_stopped"); store.finishTrip(tripId) }
        try { fused.removeLocationUpdates(fusedCallback) } catch(_:Exception) {}
        try { manager.removeUpdates(fallbackListener) } catch(_:Exception) {}
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N) try { manager.unregisterGnssStatusCallback(gnssCallback) } catch(_:Exception) {}
        if(wakeLock?.isHeld==true) wakeLock?.release()
        wakeLock=null; tripId=-1L; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun notification(message:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,RouteHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("مسیر").setContentText(message).setContentIntent(open).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    private fun createChannel() {
        if(Build.VERSION.SDK_INT>=26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL,"ضبط مسیر",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onDestroy() {
        try { fused.removeLocationUpdates(fusedCallback) } catch(_:Exception) {}
        try { manager.removeUpdates(fallbackListener) } catch(_:Exception) {}
        if(wakeLock?.isHeld==true) wakeLock?.release()
        callbackThread.quitSafely()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
