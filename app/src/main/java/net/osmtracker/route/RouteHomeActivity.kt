package net.osmtracker.route
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.osmtracker.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*
class RouteHomeActivity: AppCompatActivity() {
 private lateinit var map: MapView; private lateinit var startButton: Button; private lateinit var status: TextView
 private lateinit var speedValue: TextView; private lateinit var distanceValue: TextView; private lateinit var altitudeValue: TextView
 private lateinit var store: RouteStore; private lateinit var line: Polyline
 private var recording=false; private var receiverRegistered=false
 private val receiver=object: BroadcastReceiver() {
  override fun onReceive(context: Context?,intent: Intent?) {
   if(intent?.action!=RouteRecordingService.ACTION_LOCATION) return
   val p=GeoPoint(intent.getDoubleExtra(RouteRecordingService.EXTRA_LAT,0.0),intent.getDoubleExtra(RouteRecordingService.EXTRA_LON,0.0))
   line.addPoint(p); map.controller.animateTo(p)
   speedValue.text=String.format(Locale.getDefault(),"%.1f km/h",intent.getFloatExtra(RouteRecordingService.EXTRA_SPEED,0f)*3.6f)
   altitudeValue.text=String.format(Locale.getDefault(),"%.0f m",intent.getDoubleExtra(RouteRecordingService.EXTRA_ALT,0.0))
   distanceValue.text=String.format(Locale.getDefault(),"%.2f km",intent.getDoubleExtra(RouteRecordingService.EXTRA_DISTANCE,0.0)/1000)
   map.invalidate()
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); store=RouteStore(this)
  Configuration.getInstance().load(this,PreferenceManager.getDefaultSharedPreferences(this)); Configuration.getInstance().userAgentValue=packageName
  buildScreen(); restoreLastRoute()
  if(hasPermission()) enableMyLocation() else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),41)
 }
 private fun buildScreen() {
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(246,248,250)) }
  val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(18,14,18,14);setBackgroundColor(Color.rgb(18,39,57)) }
  header.addView(TextView(this).apply { text="ROUTE RECORDER";textSize=18f;setTextColor(Color.WHITE);letterSpacing=.08f },LinearLayout.LayoutParams(0,-2,1f))
  header.addView(TextView(this).apply { text="GPS  •  LIVE";textSize=12f;setTextColor(Color.rgb(112,220,180)) }); root.addView(header)
  map=MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);controller.setZoom(15.5);controller.setCenter(GeoPoint(50.9375,6.9603)) }
  root.addView(map,LinearLayout.LayoutParams(-1,0,1f))
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(14,10,14,12);setBackgroundColor(Color.WHITE) }
  status=TextView(this).apply { text="آمادهٔ ضبط مسیر";textSize=14f;setTextColor(Color.rgb(75,90,105));gravity=Gravity.CENTER;setPadding(0,4,0,10) };panel.addView(status)
  val stats=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER }
  val a=makeStat("0.0 km/h","سرعت"); speedValue=a.first;stats.addView(a.second,LinearLayout.LayoutParams(0,-2,1f))
  val b=makeStat("0.00 km","مسافت"); distanceValue=b.first;stats.addView(b.second,LinearLayout.LayoutParams(0,-2,1f))
  val c=makeStat("— m","ارتفاع"); altitudeValue=c.first;stats.addView(c.second,LinearLayout.LayoutParams(0,-2,1f))
  stats.addView(makeStat("GPS","وضعیت").second,LinearLayout.LayoutParams(0,-2,1f));panel.addView(stats)
  startButton=Button(this).apply { text="شروع ضبط مسیر";isAllCaps=false;setTextColor(Color.WHITE);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,145,105));setOnClickListener { if(recording) stopRecording() else requestAndStart() } }
  panel.addView(startButton,LinearLayout.LayoutParams(-1,54).apply { topMargin=12 })
  panel.addView(Button(this).apply { text="تاریخچه سفرها";isAllCaps=false;setOnClickListener { showHistory() } })
  root.addView(panel);setContentView(root)
  line=Polyline().apply { outlinePaint.color=Color.rgb(0,125,220);outlinePaint.strokeWidth=9f };map.overlays.add(line)
 }
 private fun makeStat(value: String,label: String): Pair<TextView,LinearLayout> {
  val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(2,3,2,3) }
  val v=TextView(this).apply { text=value;textSize=16f;setTextColor(Color.rgb(20,39,55));gravity=Gravity.CENTER }
  box.addView(v);box.addView(TextView(this).apply { text=label;textSize=11f;setTextColor(Color.GRAY);gravity=Gravity.CENTER })
  return Pair(v,box)
 }
 private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun enableMyLocation() {
  try { val overlay=MyLocationNewOverlay(org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(this),map);overlay.enableMyLocation();map.overlays.add(overlay)
   val lm=getSystemService(LOCATION_SERVICE) as LocationManager
   if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { map.controller.animateTo(GeoPoint(it.latitude,it.longitude)) }
  } catch(_: Exception) {}
 }
 private fun requestAndStart() {
  if(!hasPermission()) { ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),41);return }
  ContextCompat.startForegroundService(this,Intent(this,RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_START))
  recording=true;startButton.text="پایان ضبط";startButton.backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(190,65,60));status.text="در حال ضبط مسیر — GPS فعال"
 }
 private fun stopRecording() {
  startService(Intent(this,RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_STOP))
  recording=false;startButton.text="شروع ضبط مسیر";startButton.backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(20,145,105));status.text="مسیر ذخیره شد"
 }
 private fun restoreLastRoute() {
  val trip=store.trips().firstOrNull()?:return
  if(trip.endedAt>0) { val points=store.points(trip.id);line.points.clear(); line.points.addAll(points.map { GeoPoint(it.lat,it.lon) });distanceValue.text=String.format(Locale.getDefault(),"%.2f km",trip.distance/1000);if(points.isNotEmpty()) map.controller.setCenter(GeoPoint(points.last().lat,points.last().lon)) }
 }
 private fun showHistory() {
  val trips=store.trips();if(trips.isEmpty()) { Toast.makeText(this,"هنوز سفری ثبت نشده است",Toast.LENGTH_SHORT).show();return }
  val labels=trips.map { SimpleDateFormat("yyyy/MM/dd  HH:mm",Locale.getDefault()).format(Date(it.startedAt))+"  •  "+String.format("%.2f km",it.distance/1000) }.toTypedArray()
  androidx.appcompat.app.AlertDialog.Builder(this).setTitle("سفرهای ذخیره‌شده").setItems(labels) { _,i ->
   val points=store.points(trips[i].id);line.points=points.map { GeoPoint(it.lat,it.lon) };if(points.isNotEmpty()) map.controller.setCenter(GeoPoint(points.last().lat,points.last().lon));map.invalidate()
  }.setPositiveButton("بستن",null).show()
 }
 override fun onResume() { super.onResume();map.onResume();if(!receiverRegistered) { val f=IntentFilter(RouteRecordingService.ACTION_LOCATION);if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver,f);receiverRegistered=true } }
 override fun onPause() { if(receiverRegistered) { unregisterReceiver(receiver);receiverRegistered=false };map.onPause();super.onPause() }
 override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==41 && hasPermission()) { enableMyLocation() } }
}
