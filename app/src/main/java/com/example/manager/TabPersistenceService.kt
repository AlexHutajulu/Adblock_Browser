package com.example.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class TabPersistenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TabPersistenceService", "Service started to track task swipe removal")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("TabPersistenceService", "onTaskRemoved: App swiped away from recent apps (Gulir membersihkan RAM/Memori)!")
        try {
            val settings = SettingsManager(applicationContext)
            settings.clearPersistedTabs()
        } catch (e: Exception) {
            Log.e("TabPersistenceService", "Failed to clear persisted tabs on swipe away", e)
        }
        stopSelf()
    }
}
