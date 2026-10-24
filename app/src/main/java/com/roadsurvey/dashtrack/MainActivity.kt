package com.roadsurvey.dashtrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var gpsCard: LinearLayout
    private lateinit var gpsTitle: TextView
    private lateinit var gpsDetail: TextView
    private lateinit var gpsAction: Button
    private lateinit var lm: LocationManager
    private var runs: List<File> = emptyList()

    // GPS check state
    private var listening = false
    private var listenStartMs = 0L
    private var lastFix: Location? = null
    private var satsInView = 0
    private var satsUsed = 0
    private var gpsReady = false

    private val ui = Handler(Looper.getMainLooper())
    private val gpsTick = object : Runnable {
        override fun run() { refreshGpsCard(); ui.postDelayed(this, 1000) }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsInView = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startGpsCheck()
            refreshGpsCard()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "DashTrack Survey"
            textSize = 24f
            setTextColor(Color.parseColor("#C62828"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Road inventory · dashcam video + GPS trace + speed profile"
            setTextColor(Color.DKGRAY)
        })

        // ---- GPS check card ----
        gpsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        gpsTitle = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        gpsDetail = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(6))
        }
        gpsAction = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        }
        gpsCard.addView(gpsTitle)
        gpsCard.addView(gpsDetail)
        gpsCard.addView(gpsAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(gpsCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        root.addView(Button(this).apply {
            text = "●  Start new survey"
            textSize = 18f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
            setOnClickListener { onStartSurvey() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })
        root.addView(TextView(this).apply {
            text = "Previous surveys (tap to replay, long-press for upload / delete)"
            setTextColor(Color.GRAY)
        })
        empty = TextView(this).apply {
            text = "No surveys yet."
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(empty)
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Files: Android/data/$packageName/files/surveys"
            textSize = 11f
            setTextColor(Color.GRAY)
        })
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ReplayActivity::class.java)
                .putExtra(ReplayActivity.EXTRA_DIR, runs[pos].absolutePath))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val run = runs[pos]
            val signed = CloudSync.signedInEmail(this)
            val items = arrayOf("☁ Upload to cloud", "🗑 Delete", if (signed != null) "Sign out ($signed)" else "Cloud sign-in")
            AlertDialog.Builder(this)
                .setTitle(run.name)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> CloudSync.uploadAsync(this, run) { refresh() }
                        1 -> AlertDialog.Builder(this)
                            .setTitle("Delete ${run.name}?")
                            .setMessage("Video, GPS track and tags will be permanently deleted from this phone.")
                            .setPositiveButton("Delete") { _, _ -> run.deleteRecursively(); refresh() }
                            .setNegativeButton("Cancel", null)
                            .show()
                        2 -> if (signed != null) { CloudSync.signOut(this); refresh() }
                             else CloudSync.ensureLogin(this) { refresh() }
                    }
                }
                .show()
            true
        }

        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        startGpsCheck()
        ui.removeCallbacks(gpsTick)
        ui.post(gpsTick)
    }

    override fun onPause() {
        ui.removeCallbacks(gpsTick)
        stopGpsCheck()
        super.onPause()
    }

    // ---------------- GPS check ----------------

    @SuppressLint("MissingPermission")
    private fun startGpsCheck() {
        if (listening) return
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCallback, ui)
            listening = true
            listenStartMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            gpsDetail.text = "GPS error: ${e.message}"
        }
    }

    private fun stopGpsCheck() {
        if (!listening) return
        lm.removeUpdates(this)
        lm.unregisterGnssStatusCallback(gnssCallback)
        listening = false
    }

    override fun onLocationChanged(loc: Location) { lastFix = loc }
    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { startGpsCheck() }
    override fun onProviderDisabled(provider: String) { stopGpsCheck(); lastFix = null }

    private fun setCard(color: String, title: String, detail: String, action: String?, onAction: (() -> Unit)?) {
        gpsCard.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(color))
        }
        gpsTitle.text = title
        gpsDetail.text = detail
        if (action == null) gpsAction.visibility = View.GONE
        else {
            gpsAction.visibility = View.VISIBLE
            gpsAction.text = action
            gpsAction.setOnClickListener { onAction?.invoke() }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun refreshGpsCard() {
        gpsReady = false
        val red = "#C62828"; val amber = "#E65100"; val green = "#2E7D32"

        val fine = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && coarse) {
            setCard(red, "✖ Precise location is OFF for this app",
                "Android gave the app only approximate location. Open app settings → Permissions → Location → turn ON \"Use precise location\".",
                "Open app settings") { openAppSettings() }
            return
        }
        if (!fine) {
            setCard(red, "✖ Location permission not granted",
                "The app needs precise location to record the GPS trace.",
                "Grant permission") {
                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            setCard(red, "✖ Phone location (GPS) is switched OFF",
                "Turn on Location from the quick settings panel or tap below.",
                "Open location settings") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            return
        }
        startGpsCheck()

        val f = lastFix
        val ageS = if (f == null) Long.MAX_VALUE
                   else (SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1_000_000_000
        val waitS = (SystemClock.elapsedRealtime() - listenStartMs) / 1000
        val sats = "Satellites: $satsUsed used / $satsInView in view"

        when {
            f == null || ageS > 5 -> {
                val hint = when {
                    satsInView == 0 && waitS > 20 ->
                        "No satellites visible. Go outdoors or keep the phone on the dashboard under clear sky. GPS does not work indoors."
                    waitS < 90 ->
                        "First fix usually takes 30–90 s outdoors. Keep the phone still with a clear view of the sky."
                    else ->
                        "Still no fix. Check: Battery saver OFF, Location → Google Location Accuracy ON, then restart the phone if needed."
                }
                setCard(amber, "◌ Searching for GPS… ${waitS}s", "$sats\n$hint", "Location settings") {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            f.accuracy > 20f -> setCard(amber, String.format(Locale.US, "◐ Weak GPS fix (±%.0f m)", f.accuracy),
                "$sats\nWait for accuracy better than ±20 m before starting. Move away from tall buildings / flyovers.",
                null, null)
            else -> {
                gpsReady = true
                setCard(green, String.format(Locale.US, "✔ GPS ready (±%.0f m)", f.accuracy),
                    String.format(Locale.US, "%s\n%.6f N, %.6f E", sats, f.latitude, f.longitude),
                    null, null)
            }
        }
    }

    private fun onStartSurvey() {
        if (gpsReady) {
            startActivity(Intent(this, SurveyActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("GPS not ready")
            .setMessage("Without a good GPS fix the video will be recorded but the trace and tags will be missing or inaccurate. Wait for the green \"GPS ready\" card.")
            .setPositiveButton("Wait") { _, _ -> }
            .setNegativeButton("Start anyway") { _, _ ->
                startActivity(Intent(this, SurveyActivity::class.java))
            }
            .show()
    }

    // ---------------- Survey list ----------------

    private fun refresh() {
        runs = RunIO.listRuns(this)
        val labels = runs.map { d ->
            val fixes = RunIO.readTrack(d)
            val nTags = RunIO.readTags(d).size
            val km = (fixes.lastOrNull()?.distM ?: 0.0) / 1000
            val dur = RunIO.fmtTime(fixes.lastOrNull()?.tMs ?: 0)
            val mb = RunIO.videoFile(d).length() / (1024 * 1024)
            val cloud = if (CloudSync.isUploaded(d)) "☁ " else ""
            String.format(Locale.US, "%s%s\n%.2f km · %s · %d tags · %d fixes · %d MB",
                cloud, d.name, km, dur, nTags, fixes.size, mb)
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        empty.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
    }
}
