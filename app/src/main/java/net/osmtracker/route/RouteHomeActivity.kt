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

class RouteHomeActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var startButton: Button
    private lateinit var status: TextView
    private lateinit var speedValue: TextView
    private lateinit var distanceValue: TextView
    private lateinit var altitudeValue: TextView
    private lateinit var store: RouteStore
    private lateinit var line: Polyline
    private var recording = false
    private var receiverRegistered = false
    private var followLocation = true
    private var latestPoint: GeoPoint? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RouteRecordingService.ACTION_LOCATION) return
            val p = GeoPoint(
                intent.getDoubleExtra(RouteRecordingService.EXTRA_LAT, 0.0),
                intent.getDoubleExtra(RouteRecordingService.EXTRA_LON, 0.0)
            )
            latestPoint = p
            line.addPoint(p)
            if (followLocation) map.controller.animateTo(p)
            speedValue.text = String.format(Locale.getDefault(), "%.1f km/h", intent.getFloatExtra(RouteRecordingService.EXTRA_SPEED, 0f) * 3.6f)
            val hasAltitude = intent.getBooleanExtra(RouteRecordingService.EXTRA_HAS_ALT, false)
            altitudeValue.text = if (hasAltitude) String.format(Locale.getDefault(), "%.0f m", intent.getDoubleExtra(RouteRecordingService.EXTRA_ALT, 0.0)) else "—"
            distanceValue.text = String.format(Locale.getDefault(), "%.2f km", intent.getDoubleExtra(RouteRecordingService.EXTRA_DISTANCE, 0.0) / 1000)
            status.text = if (hasAltitude) "GPS فعال • ارتفاع دریافت شد" else "GPS فعال • در انتظار ارتفاع"
            map.invalidate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        buildScreen()
        restoreLastRoute()
        if (hasPermission()) enableMyLocation() else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            41
        )
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(241, 245, 248))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(Color.rgb(15, 38, 56))
        }
        header.addView(TextView(this).apply {
            text = "راه‌نگار"
            textSize = 20f
            setTextColor(Color.WHITE)
            letterSpacing = .03f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "GPS  •  LIVE"
            textSize = 12f
            setTextColor(Color.rgb(105, 230, 194))
        })
        root.addView(header)

        val mapFrame = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.5)
            controller.setCenter(GeoPoint(50.9375, 6.9603))
        }
        mapFrame.addView(map, FrameLayout.LayoutParams(-1, -1))
        val mapActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(5, 5, 5, 5)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18f
                setStroke(1, Color.rgb(205, 216, 225))
            }
        }
        mapActions.addView(mapButton("◎") { recenter() })
        mapActions.addView(mapButton("⚙") { showSettings() })
        val actionParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            setMargins(0, 18, 14, 0)
        }
        mapFrame.addView(mapActions, actionParams)
        root.addView(mapFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 11, 14, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 43, 61))
                cornerRadii = floatArrayOf(22f,22f,22f,22f,0f,0f,0f,0f)
            }
        }
        status = TextView(this).apply {
            text = "آمادهٔ ضبط مسیر"
            textSize = 13f
            setTextColor(Color.rgb(179, 205, 220))
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 10)
        }
        panel.addView(status)
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val a = makeStat("0.0 km/h", "سرعت")
        speedValue = a.first
        stats.addView(a.second, LinearLayout.LayoutParams(0, -2, 1f))
        val b = makeStat("0.00 km", "مسافت")
        distanceValue = b.first
        stats.addView(b.second, LinearLayout.LayoutParams(0, -2, 1f))
        val c = makeStat("—", "ارتفاع")
        altitudeValue = c.first
        stats.addView(c.second, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(makeStat("GPS", "وضعیت").second, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(stats)

        startButton = Button(this).apply {
            text = "شروع ضبط مسیر"
            isAllCaps = false
            textSize = 16f
            minHeight = 58
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 157, 119))
            setOnClickListener { if (recording) stopRecording() else requestAndStart() }
        }
        panel.addView(startButton, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 12 })
        val historyButton = Button(this).apply {
            text = "مسیرهای ذخیره‌شده"
            isAllCaps = false
            setTextColor(Color.rgb(230, 241, 247))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 67, 86))
            setOnClickListener { showHistory() }
        }
        panel.addView(historyButton, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 6 })
        root.addView(panel)
        setContentView(root)

        line = Polyline().apply {
            outlinePaint.color = Color.rgb(0, 132, 255)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(line)
    }

    private fun mapButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 20f
            minWidth = 48
            minHeight = 48
            setPadding(0, 0, 0, 0)
            setTextColor(Color.rgb(18, 54, 77))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { action() }
        }

    private fun makeStat(value: String, label: String): Pair<TextView, LinearLayout> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(2, 4, 2, 4)
        }
        val v = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        box.addView(v)
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.rgb(169, 195, 210))
            gravity = Gravity.CENTER
        })
        return Pair(v, box)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableMyLocation() {
        try {
            val overlay = MyLocationNewOverlay(org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(this), map)
            overlay.enableMyLocation()
            map.overlays.add(overlay)
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    latestPoint = GeoPoint(it.latitude, it.longitude)
                    map.controller.animateTo(latestPoint)
                }
            }
        } catch (_: Exception) { }
    }

    private fun recenter() {
        followLocation = true
        latestPoint?.let { map.controller.animateTo(it) }
            ?: Toast.makeText(this, "در انتظار دریافت موقعیت GPS", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val options = arrayOf(
            if (followLocation) "دنبال‌کردن خودکار موقعیت: روشن" else "دنبال‌کردن خودکار موقعیت: خاموش",
            "بازگشت به موقعیت فعلی",
            "نمایش شمال در بالای نقشه"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تنظیمات نقشه")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        followLocation = !followLocation
                        Toast.makeText(this, if (followLocation) "دنبال‌کردن موقعیت روشن شد" else "حرکت آزاد نقشه فعال شد", Toast.LENGTH_SHORT).show()
                        if (followLocation) recenter()
                    }
                    1 -> recenter()
                    2 -> {
                        map.mapOrientation = 0f
                        Toast.makeText(this, "جهت نقشه: شمال رو به بالا", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun requestAndStart() {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_START))
        recording = true
        startButton.text = "پایان ضبط"
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(190, 65, 60))
        status.text = "در حال ضبط مسیر • GPS فعال"
    }

    private fun stopRecording() {
        startService(Intent(this, RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_STOP))
        recording = false
        startButton.text = "شروع ضبط مسیر"
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 157, 119))
        status.text = "مسیر ذخیره شد"
    }

    private fun restoreLastRoute() {
        val trip = store.trips().firstOrNull() ?: return
        if (trip.endedAt > 0) {
            val points = store.points(trip.id)
            line.points.clear()
            line.points.addAll(points.map { GeoPoint(it.lat, it.lon) })
            distanceValue.text = String.format(Locale.getDefault(), "%.2f km", trip.distance / 1000)
            if (points.isNotEmpty()) {
                latestPoint = GeoPoint(points.last().lat, points.last().lon)
                map.controller.setCenter(latestPoint)
            }
        }
    }

    private fun showHistory() {
        val trips = store.trips()
        if (trips.isEmpty()) {
            Toast.makeText(this, "هنوز مسیری ذخیره نشده است", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = trips.map {
            SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(it.startedAt)) +
                "  •  " + String.format("%.2f km", it.distance / 1000)
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("مسیرهای ذخیره‌شده")
            .setItems(labels) { _, i ->
                val points = store.points(trips[i].id)
                line.points.clear()
                line.points.addAll(points.map { GeoPoint(it.lat, it.lon) })
                if (points.isNotEmpty()) {
                    latestPoint = GeoPoint(points.last().lat, points.last().lon)
                    map.controller.setCenter(latestPoint)
                }
                map.invalidate()
            }.setPositiveButton("بستن", null).show()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (!receiverRegistered) {
            val f = IntentFilter(RouteRecordingService.ACTION_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, f)
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        map.onPause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && hasPermission()) enableMyLocation()
    }
}
