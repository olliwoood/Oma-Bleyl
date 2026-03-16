package com.example.voicelauncher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.example.voicelauncher.BuildConfig
import okhttp3.*
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Prüft GitHub Releases auf neue App-Versionen und installiert Updates automatisch.
 *
 * Workflow:
 * 1. Ruft die GitHub API auf, um das neueste Release zu holen
 * 2. Vergleicht den Tag (z.B. "v2") mit dem aktuellen BuildConfig.VERSION_CODE
 * 3. Lädt bei neuer Version die APK herunter
 * 4. Öffnet den Android-Installationsdialog
 */
class AppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdater"

        // ─── HIER DEINE GITHUB-DATEN EINTRAGEN ───
        private const val GITHUB_OWNER = "DEIN_USERNAME"   // z.B. "oliverwirthgen"
        private const val GITHUB_REPO = "DEIN_REPO_NAME"   // z.B. "oma-bleyl"
        // ──────────────────────────────────────────

        private const val API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val APK_FILENAME = "update.apk"
    }

    private val client = OkHttpClient()

    /**
     * Prüft im Hintergrund, ob ein neues Release auf GitHub verfügbar ist.
     * Falls ja, wird die APK heruntergeladen und der Installationsdialog geöffnet.
     */
    fun checkForUpdate() {
        Log.d(TAG, "Prüfe auf Updates... (aktuelle Version: ${BuildConfig.VERSION_CODE})")

        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Update-Check fehlgeschlagen (kein Internet?): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "GitHub API Fehler: ${resp.code}")
                        return
                    }

                    try {
                        val json = JSONObject(resp.body!!.string())
                        val tagName = json.getString("tag_name") // z.B. "v2" oder "v15"
                        val remoteVersion = parseVersionFromTag(tagName)

                        Log.d(TAG, "GitHub Release: tag=$tagName → Version $remoteVersion, lokal=${BuildConfig.VERSION_CODE}")

                        if (remoteVersion > BuildConfig.VERSION_CODE) {
                            Log.d(TAG, "🆕 Neue Version verfügbar! $remoteVersion > ${BuildConfig.VERSION_CODE}")

                            // APK-Download-URL aus den Assets holen
                            val assets = json.getJSONArray("assets")
                            var apkUrl: String? = null
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.getString("name")
                                if (name.endsWith(".apk")) {
                                    apkUrl = asset.getString("browser_download_url")
                                    break
                                }
                            }

                            if (apkUrl != null) {
                                downloadAndInstall(apkUrl)
                            } else {
                                Log.w(TAG, "Release hat keine APK-Datei angehängt!")
                            }
                        } else {
                            Log.d(TAG, "✅ App ist aktuell.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fehler beim Parsen der GitHub-Antwort", e)
                    }
                }
            }
        })
    }

    /**
     * Parsed den Version-Tag von GitHub.
     * Unterstützt Formate wie "v2", "v15", "2", "15", "v1.2" (nimmt dann die erste Zahl)
     */
    private fun parseVersionFromTag(tag: String): Int {
        // Entferne "v" Prefix und nimm die erste Zahl
        val cleaned = tag.removePrefix("v").removePrefix("V")
        // Versuche als Int zu parsen (für "2", "15" etc.)
        cleaned.toIntOrNull()?.let { return it }
        // Fallback: Erste Zahl aus dem String extrahieren
        val match = Regex("(\\d+)").find(cleaned)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Lädt die APK von der URL herunter und startet die Installation.
     */
    private fun downloadAndInstall(apkUrl: String) {
        Log.d(TAG, "Lade APK herunter: $apkUrl")

        val request = Request.Builder()
            .url(apkUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "APK-Download fehlgeschlagen", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "APK-Download HTTP-Fehler: ${resp.code}")
                        return
                    }

                    try {
                        // APK im Cache-Verzeichnis speichern
                        val apkFile = File(context.cacheDir, APK_FILENAME)

                        // Alte APK löschen falls vorhanden
                        if (apkFile.exists()) apkFile.delete()

                        // APK schreiben
                        val body = resp.body ?: return
                        apkFile.sink().buffer().use { sink ->
                            sink.writeAll(body.source())
                        }

                        Log.d(TAG, "APK heruntergeladen: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

                        // Installation starten
                        installApk(apkFile)

                    } catch (e: Exception) {
                        Log.e(TAG, "Fehler beim Speichern der APK", e)
                    }
                }
            }
        })
    }

    /**
     * Öffnet den Android-Installationsdialog für die heruntergeladene APK.
     */
    private fun installApk(apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.d(TAG, "Starte Installation...")
            context.startActivity(installIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten der Installation", e)
        }
    }
}
