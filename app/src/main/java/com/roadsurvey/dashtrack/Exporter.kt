package com.roadsurvey.dashtrack

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes GIS-ready outputs next to the run's CSVs: GPX, KML and GeoJSON. */
object Exporter {

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun f7(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    fun writeAll(dir: File, fixes: List<Fix>, tags: List<Tag>) {
        File(dir, "route.gpx").writeText(gpx(dir.name, fixes, tags))
        File(dir, "inventory.kml").writeText(kml(dir.name, fixes, tags))
        File(dir, "inventory.geojson").writeText(geojson(fixes, tags))
    }

    private fun gpx(name: String, fixes: List<Fix>, tags: List<Tag>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="DashTrack Survey" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        for (t in tags) {
            append("""<wpt lat="${f7(t.lat)}" lon="${f7(t.lon)}"><time>${iso(t.utcMs)}</time>""")
            append("<name>${esc(t.type)}</name><desc>chainage_m=${f1(t.chainageM)}; video_s=${t.tMs / 1000.0}</desc></wpt>\n")
        }
        append("<trk><name>${esc(name)}</name><trkseg>\n")
        for (f in fixes) {
            append("""<trkpt lat="${f7(f.lat)}" lon="${f7(f.lon)}"><time>${iso(f.utcMs)}</time></trkpt>""").append('\n')
        }
        append("</trkseg></trk>\n</gpx>\n")
    }

    private fun kml(name: String, fixes: List<Fix>, tags: List<Tag>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${esc(name)}</name>""").append('\n')
        append("""<Style id="route"><LineStyle><color>ff2f2fd3</color><width>4</width></LineStyle></Style>""").append('\n')
        append("<Placemark><name>Route</name><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
        for (f in fixes) append("${f7(f.lon)},${f7(f.lat)},0 ")
        append("\n</coordinates></LineString></Placemark>\n")
        for (t in tags) {
            append("<Placemark><name>${esc(t.type)}</name>")
            append("<description>Chainage ${f1(t.chainageM)} m, video ${RunIO.fmtTime(t.tMs)}, ${iso(t.utcMs)}</description>")
            append("<ExtendedData><Data name=\"type\"><value>${esc(t.type)}</value></Data>")
            append("<Data name=\"chainage_m\"><value>${f1(t.chainageM)}</value></Data>")
            append("<Data name=\"video_t_ms\"><value>${t.tMs}</value></Data></ExtendedData>")
            append("<Point><coordinates>${f7(t.lon)},${f7(t.lat)},0</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    private fun geojson(fixes: List<Fix>, tags: List<Tag>) = buildString {
        append("""{"type":"FeatureCollection","features":[""").append('\n')
        val feats = mutableListOf<String>()
        if (fixes.size >= 2) {
            val coords = fixes.joinToString(",") { "[${f7(it.lon)},${f7(it.lat)}]" }
            feats.add(
                """{"type":"Feature","properties":{"kind":"route","length_m":${f1(fixes.last().distM)},""" +
                """"start_utc":"${iso(fixes.first().utcMs)}","end_utc":"${iso(fixes.last().utcMs)}"},""" +
                """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
            )
        }
        for (t in tags) {
            val type = t.type.replace("\"", "'")
            feats.add(
                """{"type":"Feature","properties":{"kind":"asset","type":"$type","chainage_m":${f1(t.chainageM)},""" +
                """"video_t_ms":${t.tMs},"utc":"${iso(t.utcMs)}"},""" +
                """"geometry":{"type":"Point","coordinates":[${f7(t.lon)},${f7(t.lat)}]}}"""
            )
        }
        append(feats.joinToString(",\n"))
        append("\n]}\n")
    }
}
