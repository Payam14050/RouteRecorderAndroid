package net.osmtracker.route

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.net.Uri
import java.io.File
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.osmtracker.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
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
    private var pendingExportTripId: Long = -1L
    private val requestExportGpx = 72
    private val requestExportVideo = 73
    private var pendingVideoExportTripId: Long = -1L
    private var videoDurationSeconds = 10

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
        videoDurationSeconds = PreferenceManager.getDefaultSharedPreferences(this).getInt("route_video_duration", 10)
        followLocation = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("route_follow_location", true)
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
            text = "Route"
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
        // A deliberate map drag hands control to the user; the next GPS fix must not pull the map away.
        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && followLocation) {
                followLocation = false
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("route_follow_location", false).apply()
            }
            false
        }
        // Keep map controls minimal: zoom remains available through pinch gestures.
        val menuButton = object : View(this) {
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerX = width / 2f
                val radius = 2.4f
                for (index in 0..2) {
                    canvas.drawCircle(centerX, height * (0.32f + index * 0.18f), radius, dotPaint)
                }
            }
        }.apply {
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            elevation = 0f
            setOnClickListener { anchor ->
                val menu = android.widget.PopupMenu(this@RouteHomeActivity, anchor)
                menu.menu.add(0, 1, 0, "تاریخچه سفرها")
                menu.menu.add(0, 2, 1, "تنظیمات و رنگ مسیر")
                menu.menu.add(0, 3, 2, "بازگشت به موقعیت فعلی")
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> showHistory()
                        2 -> showSettings()
                        3 -> recenter()
                    }
                    true
                }
                menu.show()
            }
        }
        val actionParams = FrameLayout.LayoutParams(38, 42, Gravity.END or Gravity.TOP).apply {
            setMargins(0, 4, 6, 0)
        }
        mapFrame.addView(menuButton, actionParams)
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

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        panel.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        startButton = Button(this).apply {
            text = "شروع مسیر"
            isAllCaps = false
            textSize = 15f
            minHeight = 0
            setPadding(4, 0, 4, 0)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 157, 119))
            setOnClickListener { if (recording) stopRecording() else requestAndStart() }
        }
        actionRow.addView(startButton, LinearLayout.LayoutParams(0, 52, 1f).apply { marginEnd = 6 })
        val historyButton = Button(this).apply {
            text = "تاریخچه سفرها"
            isAllCaps = false
            textSize = 14f
            minHeight = 0
            setPadding(4, 0, 4, 0)
            setTextColor(Color.rgb(230, 241, 247))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 67, 86))
            setOnClickListener { showHistory() }
        }
        actionRow.addView(historyButton, LinearLayout.LayoutParams(0, 52, 1f).apply { marginStart = 6 })
        root.addView(panel)
        setContentView(root)

        line = Polyline().apply {
            outlinePaint.color = PreferenceManager.getDefaultSharedPreferences(this@RouteHomeActivity).getInt("route_line_color", Color.rgb(0, 132, 255))
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(line)
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
            val blueArrow = createBlueLocationArrow()
            overlay.setDirectionArrow(blueArrow, blueArrow)
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

    private fun createBlueLocationArrow(): Bitmap {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val arrow = Path().apply {
            moveTo(24f, 3f)
            lineTo(44f, 44f)
            lineTo(24f, 34f)
            lineTo(4f, 44f)
            close()
        }
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 122, 255)
            style = Paint.Style.FILL
        })
        return bitmap
    }

    private fun recenter() {
        followLocation = true
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("route_follow_location", true).apply()
        if (latestPoint != null) map.controller.animateTo(latestPoint!!) else Toast.makeText(this, "در انتظار دریافت موقعیت GPS", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val options = arrayOf(
            "انتخاب رنگ مسیر",
            if (followLocation) "دنبال‌کردن خودکار موقعیت: روشن" else "دنبال‌کردن خودکار موقعیت: خاموش",
            "نوع نقشه: خیابانی / توپوگرافی",
            "بازگشت به موقعیت فعلی",
            "تنظیم مدت ویدئو"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تنظیمات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val colors = intArrayOf(Color.rgb(0, 132, 255), Color.rgb(0, 180, 110), Color.rgb(230, 65, 65), Color.rgb(150, 75, 220), Color.rgb(255, 145, 0), Color.BLACK)
                        val names = arrayOf("آبی", "سبز", "قرمز", "بنفش", "نارنجی", "مشکی")
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("رنگ مسیر")
                            .setItems(names) { _, selected ->
                                PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("route_line_color", colors[selected]).apply()
                                line.outlinePaint.color = colors[selected]
                                map.invalidate()
                            }.setNegativeButton("لغو", null).show()
                    }
                    1 -> {
                        followLocation = !followLocation
                        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("route_follow_location", followLocation).apply()
                        Toast.makeText(this, if (followLocation) "دنبال‌کردن موقعیت روشن شد" else "حرکت آزاد نقشه فعال شد", Toast.LENGTH_SHORT).show()
                        if (followLocation) recenter()
                    }
                    2 -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("انتخاب لایه نقشه")
                        .setItems(arrayOf("خیابانی (OpenStreetMap)", "توپوگرافی")) { _, layer ->
                            map.setTileSource(if (layer == 0) TileSourceFactory.MAPNIK else TileSourceFactory.USGS_TOPO)
                            map.invalidate()
                        }.setNegativeButton("لغو", null).show()
                    3 -> recenter()
                    4 -> {
                        val durations = arrayOf("۵ ثانیه", "۱۰ ثانیه", "۲۰ ثانیه", "۳۰ ثانیه")
                        val values = intArrayOf(5, 10, 20, 30)
                        val selected = values.indexOf(videoDurationSeconds).coerceAtLeast(0)
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("مدت بازپخش ویدئو")
                            .setSingleChoiceItems(durations, selected) { dialog, which ->
                                videoDurationSeconds = values[which]
                                PreferenceManager.getDefaultSharedPreferences(this).edit()
                                    .putInt("route_video_duration", videoDurationSeconds).apply()
                                dialog.dismiss()
                                Toast.makeText(this, "مدت ویدئو تغییر کرد", Toast.LENGTH_SHORT).show()
                            }.setNegativeButton("بستن", null).show()
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
        startButton.text = "پایان مسیر"
        startButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(190, 65, 60))
        status.text = "در حال ضبط مسیر • GPS فعال"
    }

    private fun stopRecording() {
        startService(Intent(this, RouteRecordingService::class.java).setAction(RouteRecordingService.ACTION_STOP))
        recording = false
        startButton.text = "شروع مسیر"
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
                map.controller.setCenter(latestPoint!!)
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
                val selected = trips[i]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(labels[i])
                    .setItems(arrayOf("نمایش مسیر روی نقشه", "دریافت خروجی GPX", "ساخت ویدئوی مسیر (MP4)", "حذف این سفر")) { _, action ->
                        if (action == 0) {
                            val points = store.points(selected.id)
                            line.points.clear()
                            line.points.addAll(points.map { GeoPoint(it.lat, it.lon) })
                            if (points.isNotEmpty()) {
                                latestPoint = GeoPoint(points.last().lat, points.last().lon)
                                map.controller.setCenter(latestPoint!!)
                            }
                            map.invalidate()
                        } else if (action == 1) {
                            pendingExportTripId = selected.id
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/gpx+xml"
                                putExtra(Intent.EXTRA_TITLE, "route_" + selected.id + ".gpx")
                            }
                            startActivityForResult(intent, requestExportGpx)
                        } else if (action == 2) {
                            pendingVideoExportTripId = selected.id
                            val videoIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_TITLE, "masir_trip_${selected.id}.mp4")
                            }
                            startActivityForResult(videoIntent, requestExportVideo)
                        } else {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("حذف سفر")
                                .setMessage("این سفر و تمام نقاط GPS آن حذف شود؟")
                                .setNegativeButton("انصراف", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    store.deleteTrip(selected.id)
                                    line.points.clear()
                                    restoreLastRoute()
                                    map.invalidate()
                                    Toast.makeText(this, "سفر حذف شد", Toast.LENGTH_SHORT).show()
                                }.show()
                        }
                    }.setNegativeButton("بازگشت", null).show()
            }.setPositiveButton("بستن", null).show()
    }

    private fun exportTripVideo(tripId: Long, destination: Uri) {
        val points = store.points(tripId)
        if (points.size < 2) {
            Toast.makeText(this, "برای ساخت ویدئو حداقل دو نقطه GPS لازم است", Toast.LENGTH_LONG).show()
            return
        }

        val north = points.maxOf { it.lat }
        val south = points.minOf { it.lat }
        val east = points.maxOf { it.lon }
        val west = points.minOf { it.lon }
        val latPadding = maxOf((north - south) * 0.12, 0.0005)
        val lonPadding = maxOf((east - west) * 0.12, 0.0005)
        val bounds = BoundingBox(north + latPadding, east + lonPadding, south - latPadding, west - lonPadding)

        line.points.clear()
        map.zoomToBoundingBox(bounds, true)
        map.invalidate()
        status.text = "در حال آماده‌سازی تصویر مسیر…"

        map.postDelayed({
            var background: Bitmap? = null
            val output = File(cacheDir, "masir_trip_$tripId.mp4")
            try {
                if (map.width < 2 || map.height < 2) throw IllegalStateException("اندازه نقشه برای خروجی ویدئو مناسب نیست")
                background = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
                map.draw(Canvas(background))
                val projection = map.projection
                val screenPoints = points.map { point ->
                    val pixel = projection.toPixels(GeoPoint(point.lat, point.lon), Point())
                    PointF(pixel.x.toFloat(), pixel.y.toFloat())
                }
                line.points.clear()
                line.points.addAll(points.map { GeoPoint(it.lat, it.lon) })
                map.invalidate()
                status.text = "در حال ساخت ویدئوی مسیر…"

                val capturedBackground = requireNotNull(background)
                val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("ساخت ویدئوی مسیر")
                    .setMessage("در حال ساخت ویدئو…")
                    .setPositiveButton("مکث", null)
                    .setNegativeButton("ادامه در پس‌زمینه", null)
                    .create()
                progressDialog.setCancelable(false)
                progressDialog.setCanceledOnTouchOutside(false)
                progressDialog.setOnShowListener {
                    progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                        val paused = !RouteVideoExporter.isPaused()
                        RouteVideoExporter.setPaused(paused)
                        progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = if (paused) "ادامه" else "مکث"
                        progressDialog.setMessage(if (paused) "ساخت ویدئو متوقف شده است" else "در حال ساخت ویدئو…")
                    }
                    progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        RouteVideoExporter.setPaused(false)
                        progressDialog.dismiss()
                    }
                }
                progressDialog.show()
                RouteVideoExporter.export(capturedBackground, screenPoints, output, line.outlinePaint.color, videoDurationSeconds) { error ->
                    runOnUiThread {
                        try {
                            if (progressDialog.isShowing) progressDialog.dismiss()
                            if (error != null) {
                                Toast.makeText(this, "ساخت ویدئو ناموفق بود: $error", Toast.LENGTH_LONG).show()
                            } else {
                                val copied = contentResolver.openOutputStream(destination)?.use { stream ->
                                    output.inputStream().use { input -> input.copyTo(stream) }
                                }
                                if (copied == null) throw IllegalStateException("امکان نوشتن فایل انتخاب‌شده وجود ندارد")
                                Toast.makeText(this, "ویدئوی مسیر با موفقیت ذخیره شد", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "ذخیره ویدئو ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        } finally {
                            capturedBackground.recycle()
                            output.delete()
                            status.text = "مسیر آمادهٔ مرور است"
                        }
                    }
                }
            } catch (e: Exception) {
                background?.recycle()
                output.delete()
                status.text = "آمادهٔ ضبط مسیر"
                Toast.makeText(this, "آماده‌سازی ویدئو ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, 1400L)
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

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestExportVideo && resultCode == RESULT_OK && data?.data != null && pendingVideoExportTripId >= 0) {
            val tripId = pendingVideoExportTripId
            pendingVideoExportTripId = -1L
            exportTripVideo(tripId, data.data!!)
            return
        }
        if (requestCode != requestExportGpx || resultCode != RESULT_OK || data?.data == null || pendingExportTripId < 0) return
        try {
            val points = store.points(pendingExportTripId)
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val gpx = StringBuilder()
            gpx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            gpx.append("<gpx version=\"1.1\" creator=\"Route Recorder Android\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            gpx.append("<trk><name>Route ").append(pendingExportTripId).append("</name><trkseg>\n")
            for (point in points) {
                gpx.append("<trkpt lat=\"").append(point.lat).append("\" lon=\"").append(point.lon).append("\">")
                if (!point.ele.isNaN()) gpx.append("<ele>").append(point.ele).append("</ele>")
                gpx.append("<time>").append(utc.format(Date(point.time))).append("</time></trkpt>\n")
            }
            gpx.append("</trkseg></trk></gpx>")
            contentResolver.openOutputStream(data.data!!)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer?.write(gpx.toString())
            }
            Toast.makeText(this, "فایل GPX ذخیره شد (" + points.size + " نقطه)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ذخیره GPX ناموفق بود: " + e.localizedMessage, Toast.LENGTH_LONG).show()
        } finally {
            pendingExportTripId = -1L
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && hasPermission()) enableMyLocation()
    }
}
