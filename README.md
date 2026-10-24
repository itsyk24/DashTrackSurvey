# DashTrack Survey — road inventory app (Android)

Phone records dashcam video + 1 Hz GPS, with one-tap asset tagging.
Replay shows map trace (left), video (right), speed profile (bottom), all synced.

## Get the APK without installing anything
1. Create a free GitHub account → New repository (private is fine).
2. "uploading an existing file" → drag in ALL contents of this folder
   (including the hidden `.github` folder; on Windows enable "show hidden files").
   Commit.
3. Open the **Actions** tab → "Build APK" runs automatically (~5–8 min).
4. Click the finished run → download **DashTrack-Survey-apk** (a zip) → unzip → `app-debug.apk`.
5. Copy to phone, open it, allow "Install unknown apps".

## Or build locally
Open the folder in Android Studio → Build → Build APK(s).

## Output per survey (Android/data/com.roadsurvey.dashtrack/files/surveys/RUN_yyyyMMdd_HHmmss/)
- video.mp4 — 1080p (falls back lower if unsupported)
- track.csv — video_t_ms, lat, lon, speed_kmh, heading, accuracy, utc_ms, dist_m
- tags.csv — video_t_ms, type, lat, lon, chainage_m, utc_ms
- route.gpx, inventory.kml, inventory.geojson — written on Export

## Customise asset types
Edit `TAG_TYPES` in `app/src/main/java/com/roadsurvey/dashtrack/Model.kt`.
