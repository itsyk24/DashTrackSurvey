package com.roadsurvey.dashtrack

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.res.ColorStateList
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var runs: List<File> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "DashTrack Survey"
            textSize = 24f
            setTextColor(Color.parseColor("#C62828"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Road inventory · dashcam video + GPS trace + speed profile"
            setTextColor(Color.DKGRAY)
        })
        root.addView(Button(this).apply {
            text = "●  Start new survey"
            textSize = 18f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
            setOnClickListener { startActivity(Intent(this@MainActivity, SurveyActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(16); bottomMargin = dp(12)
        })
        root.addView(TextView(this).apply {
            text = "Previous surveys (tap to replay, long-press to delete)"
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
            AlertDialog.Builder(this)
                .setTitle("Delete ${run.name}?")
                .setMessage("Video, GPS track and tags will be permanently deleted.")
                .setPositiveButton("Delete") { _, _ -> run.deleteRecursively(); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        runs = RunIO.listRuns(this)
        val labels = runs.map { d ->
            val fixes = RunIO.readTrack(d)
            val nTags = RunIO.readTags(d).size
            val km = (fixes.lastOrNull()?.distM ?: 0.0) / 1000
            val dur = RunIO.fmtTime(fixes.lastOrNull()?.tMs ?: 0)
            val mb = File(d, RunIO.VIDEO).length() / (1024 * 1024)
            String.format(Locale.US, "%s\n%.2f km · %s · %d tags · %d MB", d.name, km, dur, nTags, mb)
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        empty.visibility = if (runs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
