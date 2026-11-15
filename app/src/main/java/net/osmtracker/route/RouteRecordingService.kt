package net.osmtracker.route
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import net.osmtracker.R
import kotlin.math.roundToInt
class RouteRecordingService: Service(),LocationListener {
 companion object {
  const val ACTION_START="net.osmtracker.route.START"; const val ACTION_STOP="net.osmtracker.route.STOP"; const val ACTION_LOCATION="net.osmtracker.route.LOCATION"
  const val EXTRA_LAT="lat"; const val EXTRA_LON="lon"; const val EXTRA_ALT="alt"; const val EXTRA_HAS_ALT="has_alt"; const val EXTRA_SPEED="speed"; const val EXTRA_DISTANCE="distance"
  private const val CHANNEL="route_recording"; private const val NOTIFICATION=731
 }
 private lateinit var manager: LocationManager; private lateinit var store: RouteStore
 private var tripId=-1L; private var previous: Location?=null; private var distance=0.0
 override fun onCreate() { super.onCreate(); manager=getSystemService(LOCATION_SERVICE) as LocationManager; store=RouteStore(this); createChannel() }
 override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
  if(intent?.action==ACTION_STOP) { stopRecording(); return START_NOT_STICKY }
  if(intent?.action==ACTION_START && tripId<0) {
   tripId=store.startTrip()
   startForeground(NOTIFICATION,notification("در انتظار دریافت موقعیت GPS"))
   try {
    val fine=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    val coarse=checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    if(fine||coarse) { try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,1f,this) } catch(_: Exception) {}; try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000L,5f,this) } catch(_: Exception) {} } else stopRecording()
   } catch(_: SecurityException) { stopRecording() }
  }
  return START_STICKY
 }
 override fun onLocationChanged(location: Location) {
  // Ignore low-confidence fixes so a GPS jump cannot corrupt the saved track or distance.
  if (!location.hasAccuracy() || location.accuracy > 50f) return
  val old=previous
  if (old != null && location.time >= old.time && location.time - old.time <= 30_000L) {
   distance += old.distanceTo(location).toDouble()
  }
  previous=location
  val p=RoutePoint(location.latitude,location.longitude,if(location.hasAltitude()) location.altitude else Double.NaN,location.time,if(location.hasSpeed()) location.speed else 0f)
  if(tripId>=0) store.addPoint(tripId,p,distance)
  sendBroadcast(Intent(ACTION_LOCATION).setPackage(packageName).apply { putExtra(EXTRA_LAT,p.lat);putExtra(EXTRA_LON,p.lon);putExtra(EXTRA_ALT,if(p.ele.isNaN()) 0.0 else p.ele);putExtra(EXTRA_HAS_ALT,location.hasAltitude());putExtra(EXTRA_SPEED,p.speed);putExtra(EXTRA_DISTANCE,distance) })
  val speed=(p.speed*3.6f).roundToInt()
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION,notification("$speed km/h  •  "+String.format("%.2f km",distance/1000)))
 }
 private fun stopRecording() {
  try { manager.removeUpdates(this) } catch(_: Exception) {}
  if(tripId>=0) store.finishTrip(tripId)
  tripId=-1L; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
 }
 private fun notification(message: String): Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,RouteHomeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_track).setContentTitle("Route Recorder").setContentText(message).setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
 }
 private fun createChannel() { if(Build.VERSION.SDK_INT>=26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"ضبط مسیر",NotificationManager.IMPORTANCE_LOW)) }
 override fun onBind(intent: Intent?): IBinder?=null
 @Deprecated("Deprecated") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) {}
 override fun onProviderEnabled(provider: String) {}
 override fun onProviderDisabled(provider: String) {}
}
