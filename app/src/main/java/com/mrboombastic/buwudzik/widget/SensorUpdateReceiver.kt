package com.mrboombastic.buwudzik.widget

import com.mrboombastic.buwudzik.utils.AppLogger


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

class SensorUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d("SensorUpdateReceiver", "Alarm fired! Enqueuing worker.")
        val workRequest = OneTimeWorkRequest.Builder(SensorUpdateWorker::class.java).build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}


