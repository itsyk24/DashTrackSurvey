package com.roadsurvey.dashtrack

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Uploads a run's GPS track + tags to Supabase. Video is NOT uploaded (it goes to OneDrive). */
object CloudSync {

    const val MARKER = "uploaded.txt"

    data class Config(val url: String, val key: String)

    fun config(ctx: Context): Config? = try {
        val j = JSONObject(ctx.assets.open("cloud.json").bufferedReader().readText())
        val url = j.getString("supabase_url").trimEnd('/')
        val key = j.getString("supabase_key")
        if (url.contains("YOUR-PROJECT") || key.startsWith("PASTE")) null else Config(url, key)
    } catch (_: Exception) { null }

    fun isUploaded(dir: File) = File(dir, MARKER).exists()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun signedInEmail(ctx: Context): String? = prefs(ctx).getString("email", null)

    fun signOut(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** Asks for email/password once (stored on this phone), then runs [then]. */
    fun ensureLogin(a: Activity, then: () -> Unit) {
        if (config(a) == null) {
            AlertDialog.Builder(a).setTitle("Cloud not configured")
                .setMessage("Edit app/src/main/assets/cloud.json in GitHub with your Supabase URL and key, then rebuild the app.")
                .setPositiveButton("OK", null).show()
            return
        }
        val p = prefs(a)
        if (p.getString("email", null) != null && p.getString("password", null) != null) { then(); return }
        val email = EditText(a).apply { hint = "Email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pass = EditText(a).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(email); addView(pass)
        }
        AlertDialog.Builder(a).setTitle("Sign in to DashTrack cloud").setView(box)
            .setPositiveButton("Sign in") { _, _ ->
                val e = email.text.toString().trim()
                val pw = pass.text.toString()
                Thread {
                    try {
                        login(config(a)!!, e, pw)
                        p.edit().putString("email", e).putString("password", pw).apply()
                        a.runOnUiThread { Toast.makeText(a, "Signed in as $e", Toast.LENGTH_SHORT).show(); then() }
                    } catch (ex: Exception) {
                        a.runOnUiThread {
                            Toast.makeText(a, "Sign-in failed: ${ex.message?.take(120)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Upload in background; reports result on UI thread. */
    fun uploadAsync(a: Activity, dir: File, done: (Boolean) -> Unit) {
        ensureLogin(a) {
            Toast.makeText(a, "Uploading ${dir.name}…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = try { upload(a, dir); null } catch (e: Exception) { e.message ?: e.toString() }
                a.runOnUiThread {
                    if (msg == null) Toast.makeText(a, "Uploaded ${dir.name} ✔", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(a, "Upload failed: ${msg.take(160)}", Toast.LENGTH_LONG).show()
                    done(msg == null)
                }
            }.start()
        }
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun login(cfg: Config, email: String, password: String): String {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val res = http("POST", "${cfg.url}/auth/v1/token?grant_type=password", cfg.key, null, body)
        return JSONObject(res).getString("access_token")
    }

    private fun upload(ctx: Context, dir: File) {
        val cfg = config(ctx) ?: throw IOException("cloud.json not configured")
        val p = prefs(ctx)
        val token = login(cfg, p.getString("email", "")!!, p.getString("password", "")!!)

        val fixes = RunIO.readTrack(dir)
        val tags = RunIO.readTags(dir)
        val track = JSONArray()
        for (f in fixes) {
            track.put(JSONArray().put(f.tMs).put(f.lat).put(f.lon).put(f.speedKmh.toDouble())
                .put(f.heading.toDouble()).put(f.accuracy.toDouble()).put(f.utcMs).put(f.distM))
        }
        val survey = JSONObject()
            .put("run_name", dir.name)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("duration_ms", fixes.lastOrNull()?.tMs ?: 0)
            .put("length_m", fixes.lastOrNull()?.distM ?: 0.0)
            .put("n_fixes", fixes.size)
            .put("n_tags", tags.size)
            .put("track", track)
        fixes.firstOrNull()?.let { survey.put("started_utc", iso(it.utcMs)) }

        val res = http(
            "POST", "${cfg.url}/rest/v1/surveys?on_conflict=run_name", cfg.key, token,
            JSONArray().put(survey).toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
        )
        val id = JSONArray(res).getJSONObject(0).getString("id")

        // Replace this survey's tags
        http("DELETE", "${cfg.url}/rest/v1/tags?survey_id=eq.$id", cfg.key, token, null)
        if (tags.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tags) {
                arr.put(JSONObject().put("survey_id", id).put("t_ms", t.tMs).put("type", t.type)
                    .put("lat", t.lat).put("lon", t.lon).put("chainage_m", t.chainageM).put("utc", iso(t.utcMs)))
            }
            http("POST", "${cfg.url}/rest/v1/tags", cfg.key, token, arr.toString(),
                mapOf("Prefer" to "return=minimal"))
        }
        File(dir, MARKER).writeText(iso(System.currentTimeMillis()))
    }

    private fun http(
        method: String, url: String, key: String, token: String?, body: String?,
        extra: Map<String, String> = emptyMap()
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("apikey", key)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
