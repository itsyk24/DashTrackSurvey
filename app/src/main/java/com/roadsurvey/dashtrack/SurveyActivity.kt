package com.roadsurvey.dashtrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records video from the back camera and logs GPS at 1 Hz. Every GPS fix is stamped
 * with its offset from the first video frame (video_t_ms), so replay is frame-synced.
 */
class SurveyActivity : AppCompatActivity(), LocationListener {

    private lateinit var preview: PreviewView
    private lateinit var hud: TextView
    private lateinit var btnRecord: Button
    private lateinit var lm: LocationManager

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var startElapsedMs = -1L
    private var calibrated = false

    private var trackW: BufferedWriter? = null
    private var tagW: BufferedWriter? = null
    private var lastLoc: Location? = null
    private var lastLogged: Fix? = null
    private var distM = 0.0
    private var tagCount = 0
    private var satsInView = 0
    private var satsUsed = 0
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsInView = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val hudTick = object : Runnable {
        override fun run() {
            updateHud(); ui.postDelayed(this, 500)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.CAMERA) && hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startAll()
            } else {
                toast("Camera and PRECISE location permissions are required")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_survey)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        preview = findViewById(R.id.preview)
        hud = findViewById(R.id.hud)
        btnRecord = findViewById(R.id.btnRecord)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        buildTagButtons()
        btnRecord.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (recording == null) startRecording() else stopRecording()
        }

        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startAll() {
        startCamera()
        startGps()
        ui.removeCallbacks(hudTick)
        ui.post(hudTick)
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, vc)
                videoCapture = vc
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return toast("Camera not ready yet")
        if (lastLoc == null) toast("No GPS fix yet — recording anyway, track starts when fix arrives")

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(RunIO.surveysDir(this), "RUN_$stamp").apply { mkdirs() }
        trackW = File(dir, RunIO.TRACK).bufferedWriter().apply { write(Fix.HEADER); newLine() }
        tagW = File(dir, RunIO.TAGS).bufferedWriter().apply { write(Tag.HEADER); newLine() }
        distM = 0.0
        tagCount = 0
        lastLogged = null
        startElapsedMs = -1
        calibrated = false

        val out = FileOutputOptions.Builder(File(dir, "${dir.name}.mp4")).build()
        var pending = vc.output.prepareRecording(this, out)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev) }

        btnRecord.text = "■ STOP"
        btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#424242"))
    }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> startElapsedMs = SystemClock.elapsedRealtime()
            is VideoRecordEvent.Status -> {
                // Refine the sync point using the recorder's own duration counter.
                if (!calibrated) {
                    val d = ev.recordingStats.recordedDurationNanos / 1_000_000
                    if (d > 0) {
                        startElapsedMs = SystemClock.elapsedRealtime() - d
                        calibrated = true
                    }
                }
            }
            is VideoRecordEvent.Finalize -> {
                closeWriters()
                if (ev.hasError()) toast("Video saved with error code ${ev.error}")
                else toast("Survey saved")
            }
            else -> {}
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        btnRecord.text = "● START"
        btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
    }

    private fun closeWriters() {
        try { trackW?.close() } catch (_: Exception) {}
        try { tagW?.close() } catch (_: Exception) {}
        trackW = null
        tagW = null
    }

    // ---------------- GPS ----------------

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("Turn ON device location (GPS)")
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        try { lm.registerGnssStatusCallback(gnssCallback, ui) } catch (_: Exception) {}
    }

    override fun onLocationChanged(loc: Location) {
        lastLoc = loc
        if (recording == null || startElapsedMs < 0) return
        val t = loc.elapsedRealtimeNanos / 1_000_000 - startElapsedMs
        if (t < 0) return

        val prev = lastLogged
        if (prev != null) {
            val step = RunIO.haversine(prev.lat, prev.lon, loc.latitude, loc.longitude)
            val stationaryJitter = loc.speed < 0.5f && step < 5.0
            if (!stationaryJitter && loc.accuracy < 30f) distM += step
        }
        val f = Fix(
            tMs = t,
            lat = loc.latitude,
            lon = loc.longitude,
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
            heading = if (loc.hasBearing()) loc.bearing else 0f,
            accuracy = loc.accuracy,
            utcMs = loc.time,
            distM = distM
        )
        trackW?.apply { write(f.toCsv()); newLine(); flush() }
        lastLogged = f
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { toast("GPS turned off!") }

    // ---------------- Tagging ----------------

    private fun buildTagButtons() {
        val panel = findViewById<LinearLayout>(R.id.tagPanel)
        TAG_TYPES.forEach { type ->
            val b = Button(this).apply {
                text = type
                isAllCaps = false
                textSize = 14f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(tagColor(type))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    addTag(type)
                }
            }
            panel.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(2)
            })
        }
    }

    private fun addTag(type: String) {
        if (recording == null || startElapsedMs < 0) return toast("Start recording first")
        val loc = lastLoc ?: return toast("Waiting for GPS fix")
        val t = SystemClock.elapsedRealtime() - startElapsedMs
        val tag = Tag(t, type, loc.latitude, loc.longitude, distM, System.currentTimeMillis())
        tagW?.apply { write(tag.toCsv()); newLine(); flush() }
        tagCount++
        toast(String.format(Locale.US, "%s @ %.3f km", type, distM / 1000))
    }

    // ---------------- HUD ----------------

    private fun updateHud() {
        val sb = StringBuilder()
        if (recording != null && startElapsedMs > 0) {
            sb.append("● REC ").append(RunIO.fmtTime(SystemClock.elapsedRealtime() - startElapsedMs)).append('\n')
        } else sb.append("READY\n")
        val l = lastLoc
        if (l == null) {
            sb.append("GPS: searching…  sats $satsUsed/$satsInView\n")
        } else {
            val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000
            sb.append(String.format(Locale.US, "%.0f km/h\n", l.speed * 3.6))
            sb.append(String.format(Locale.US, "%.6f N %.6f E\n", l.latitude, l.longitude))
            sb.append(String.format(Locale.US, "acc ±%.0f m  age %ds  sats %d/%d\n", l.accuracy, age, satsUsed, satsInView))
        }
        sb.append(String.format(Locale.US, "Dist %.2f km  Tags %d", distM / 1000, tagCount))
        hud.text = sb
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        recording?.stop()
        recording = null
        lm.removeUpdates(this)
        try { lm.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
        closeWriters()
        super.onDestroy()
    }
}
