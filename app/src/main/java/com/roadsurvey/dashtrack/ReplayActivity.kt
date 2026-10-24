package com.roadsurvey.dashtrack

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Map trace (left) + video (right) + speed profile (bottom), all synced to video time. */
class ReplayActivity : AppCompatActivity() {

    companion object { const val EXTRA_DIR = "dir" }

    private lateinit var dir: File
    private lateinit var map: MapView
    private lateinit var video: VideoView
    private lateinit var profile: SpeedProfileView
    private lateinit var btnPlay: Button
    private lateinit var btnFollow: Button
    private lateinit var timeText: TextView
    private lateinit var cardSpeed: TextView
    private lateinit var cardLat: TextView
    private lateinit var cardLon: TextView
    private lateinit var cardTime: TextView
    private lateinit var vehicle: Marker

    private var fixes: List<Fix> = emptyList()
    private var tags: List<Tag> = emptyList()
    private var player: MediaPlayer? = null
    private var videoDur = 0L
    private var follow = true

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (videoDur > 0 && video.isPlaying) updateAt(video.currentPosition.toLong())
            ui.postDelayed(this, 100)
        }
    }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
        }
        setContentView(R.layout.activity_replay)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        map = findViewById(R.id.map)
        video = findViewById(R.id.video)
        profile = findViewById(R.id.profile)
        btnPlay = findViewById(R.id.btnPlay)
        btnFollow = findViewById(R.id.btnFollow)
        timeText = findViewById(R.id.timeText)
        cardSpeed = findViewById(R.id.cardSpeed)
        cardLat = findViewById(R.id.cardLat)
        cardLon = findViewById(R.id.cardLon)
        cardTime = findViewById(R.id.cardTime)

        fixes = RunIO.readTrack(dir)
        tags = RunIO.readTags(dir)
        findViewById<TextView>(R.id.title).text = String.format(
            Locale.US, "%s · %.2f km · %d fixes · %d tags",
            dir.name, (fixes.lastOrNull()?.distM ?: 0.0) / 1000, fixes.size, tags.size
        )

        setupMap()
        setupProfile()
        setupVideo()
        setupButtons()
        updateAt(0)
    }

    // ---------------- Map ----------------

    private fun dot(color: Int, sizeDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2f).toInt(), Color.WHITE)
        val s = dp(sizeDp).toInt()
        setSize(s, s)
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.controller.setZoom(if (fixes.isEmpty()) 4.0 else 17.0)

        // Tap anywhere on/near the route to jump the video there.
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { seekToNearest(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        val pts = fixes.map { GeoPoint(it.lat, it.lon) }
        if (pts.size >= 2) {
            val line = Polyline(map).apply {
                setPoints(pts)
                outlinePaint.color = Color.parseColor("#D32F2F")
                outlinePaint.strokeWidth = dp(5f)
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            line.setOnClickListener { _, _, p -> seekToNearest(p); true }
            map.overlays.add(line)
        }

        for (t in tags) {
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(t.lat, t.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(tagColor(t.type), 14f)
                title = t.type
                snippet = String.format(Locale.US, "Ch %.3f km · video %s", t.chainageM / 1000, RunIO.fmtTime(t.tMs))
                setOnMarkerClickListener { m, _ -> m.showInfoWindow(); seekTo(t.tMs); true }
            })
        }

        vehicle = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dot(Color.parseColor("#B71C1C"), 20f)
            setOnMarkerClickListener { _, _ -> true }
        }
        pts.firstOrNull()?.let { vehicle.position = it; map.controller.setCenter(it) }
        map.overlays.add(vehicle)
    }

    private fun fitRoute() {
        if (fixes.size < 2) return
        val box = BoundingBox.fromGeoPoints(fixes.map { GeoPoint(it.lat, it.lon) })
        map.zoomToBoundingBox(box, false, dp(30f).toInt())
    }

    private fun seekToNearest(p: GeoPoint) {
        if (fixes.isEmpty()) return
        var best = fixes[0]
        var bestD = Double.MAX_VALUE
        for (f in fixes) {
            val d = RunIO.haversine(p.latitude, p.longitude, f.lat, f.lon)
            if (d < bestD) { bestD = d; best = f }
        }
        if (bestD < 80) seekTo(best.tMs)
    }

    // ---------------- Video ----------------

    private fun setupVideo() {
        val vf = File(dir, RunIO.VIDEO)
        if (!vf.exists()) { toast("No video file in this run"); return }
        video.setVideoURI(Uri.fromFile(vf))
        video.setOnPreparedListener { mp ->
            player = mp
            videoDur = video.duration.toLong()
            profile.durationMs = maxOf(videoDur, fixes.lastOrNull()?.tMs ?: 0L, 1L)
            profile.invalidate()
            video.start()
            btnPlay.text = "❚❚"
        }
        video.setOnCompletionListener { btnPlay.text = "▶" }
        video.setOnErrorListener { _, _, _ -> toast("Cannot play this video"); true }
    }

    private fun seekTo(ms: Long) {
        val c = if (videoDur > 0) ms.coerceIn(0, videoDur) else ms.coerceAtLeast(0)
        val mp = player
        if (mp != null) {
            try { mp.seekTo(c, MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) { video.seekTo(c.toInt()) }
        } else video.seekTo(c.toInt())
        updateAt(c)
    }

    // ---------------- Sync ----------------

    private fun interp(t: Long): Fix? {
        if (fixes.isEmpty()) return null
        if (t <= fixes.first().tMs) return fixes.first()
        if (t >= fixes.last().tMs) return fixes.last()
        var lo = 0
        var hi = fixes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (fixes[mid].tMs <= t) lo = mid else hi = mid
        }
        val a = fixes[lo]
        val b = fixes[hi]
        val k = (t - a.tMs).toDouble() / (b.tMs - a.tMs).coerceAtLeast(1)
        return a.copy(
            tMs = t,
            lat = a.lat + (b.lat - a.lat) * k,
            lon = a.lon + (b.lon - a.lon) * k,
            speedKmh = (a.speedKmh + (b.speedKmh - a.speedKmh) * k).toFloat(),
            utcMs = a.utcMs + (t - a.tMs),
            distM = a.distM + (b.distM - a.distM) * k
        )
    }

    private fun card(label: String, value: String): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(label)
        sb.setSpan(RelativeSizeSpan(0.75f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.GRAY), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')
        val start = sb.length
        sb.append(value)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, start + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun updateAt(pos: Long) {
        timeText.text = "${RunIO.fmtTime(pos)} / ${RunIO.fmtTime(videoDur)}"
        profile.cursorMs = pos
        profile.invalidate()
        val f = interp(pos) ?: run {
            cardSpeed.text = card("SPEED", "— km/h")
            cardLat.text = card("LATITUDE", "—")
            cardLon.text = card("LONGITUDE", "—")
            cardTime.text = card("CHAINAGE", "—")
            return
        }
        val gp = GeoPoint(f.lat, f.lon)
        vehicle.position = gp
        if (follow) map.controller.setCenter(gp)
        map.invalidate()

        cardSpeed.text = card("SPEED", String.format(Locale.US, "%.0f km/h", f.speedKmh))
        cardLat.text = card("LATITUDE", String.format(Locale.US, "%.5f° N", f.lat))
        cardLon.text = card("LONGITUDE", String.format(Locale.US, "%.5f° E", f.lon))
        cardTime.text = card(
            "CHAINAGE · TIME",
            String.format(Locale.US, "%.3f km · %s", f.distM / 1000, clock.format(Date(f.utcMs)))
        )
    }

    // ---------------- Controls ----------------

    private fun setupProfile() {
        profile.fixes = fixes
        profile.tags = tags
        profile.durationMs = maxOf(fixes.lastOrNull()?.tMs ?: 1L, 1L)
        profile.onSeek = { seekTo(it) }
    }

    private fun setupButtons() {
        btnPlay.setOnClickListener {
            if (video.isPlaying) { video.pause(); btnPlay.text = "▶" }
            else { video.start(); btnPlay.text = "❚❚" }
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            seekTo(video.currentPosition - 10_000L)
        }
        findViewById<Button>(R.id.btnFwd).setOnClickListener {
            seekTo(video.currentPosition + 10_000L)
        }
        btnFollow.setOnClickListener { setFollow(!follow) }
        findViewById<Button>(R.id.btnFit).setOnClickListener { setFollow(false); fitRoute() }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Export ${dir.name}")
                .setItems(arrayOf("Data only (CSV, GPX, KML, GeoJSON)", "Data + video")) { _, which ->
                    share(which == 1)
                }
                .show()
        }
    }

    private fun setFollow(on: Boolean) {
        follow = on
        btnFollow.text = if (on) "Follow ✓" else "Follow"
    }

    private fun share(includeVideo: Boolean) {
        try {
            Exporter.writeAll(dir, fixes, tags)
            val files = dir.listFiles()?.filter { it.isFile && (includeVideo || it.name != RunIO.VIDEO) } ?: return
            val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
            val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Share survey ${dir.name}"))
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onResume() {
        super.onResume()
        map.onResume()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        map.onPause()
        if (video.isPlaying) { video.pause(); btnPlay.text = "▶" }
        super.onPause()
    }
}
