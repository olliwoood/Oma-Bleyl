# 🔄 App-Update Anleitung (Remote via GitHub)

Die App prüft bei jedem Start automatisch auf neue Versionen auf GitHub.
Oma muss nur einmal auf "Installieren" tippen.

## Update veröffentlichen

### 1. Version hochzählen

In `app/build.gradle.kts` den `versionCode` erhöhen:

```kotlin
versionCode = 2  // war vorher 1
```

### 2. APK bauen

```bash
.\gradlew.bat assembleDebug
```

Die fertige APK liegt unter:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. GitHub Release erstellen

1. Gehe zu [github.com/olliwoood/Oma-Bleyl/releases/new](https://github.com/olliwoood/Oma-Bleyl/releases/new)
2. **Tag**: `v2` (muss der `versionCode`-Nummer entsprechen)
3. **Titel**: z.B. `Version 2 – Bugfixes`
4. **APK anhängen**: Die `app-debug.apk` per Drag & Drop hinzufügen
5. **Publish Release** klicken

### 4. Fertig!

Beim nächsten App-Start auf Omas Handy:
- App erkennt neue Version automatisch
- APK wird heruntergeladen
- Installationsdialog erscheint
- Oma tippt auf "Installieren" ✅

## Sicherheit

- **API-Key** steht in `local.properties` (wird per `.gitignore` ausgeschlossen)
- **API-Key-Einschränkung** in Google Cloud Console auf Paketname + SHA-1 Fingerprint
- **SHA-1 (Debug)**: `2F:A1:A4:72:23:01:96:0A:C3:C5:E1:8B:39:17:B3:69:C6:02:13:F7`
- **Paketname**: `com.example.voicelauncher`

## Technische Details

Die Update-Logik befindet sich in:
- `app/src/main/java/com/example/voicelauncher/update/AppUpdater.kt`

Sie ruft `https://api.github.com/repos/olliwoood/Oma-Bleyl/releases/latest` auf,
vergleicht den Tag mit `BuildConfig.VERSION_CODE` und lädt bei Bedarf die APK herunter.
