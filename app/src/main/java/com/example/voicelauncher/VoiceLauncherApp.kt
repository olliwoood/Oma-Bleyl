package com.example.voicelauncher

import android.app.Application
import com.example.voicelauncher.data.AppDatabase

class VoiceLauncherApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
}
