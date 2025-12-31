package com.mrboombastic.buwudzik

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BluetoothStateReceiver(
    private val onStateChanged: (Boolean) -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val isEnabled = state == BluetoothAdapter.STATE_ON
            onStateChanged(isEnabled)
        }
    }
}
