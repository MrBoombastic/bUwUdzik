package com.mrboombastic.buwudzik.data

import android.content.Context

/**
 * Clears all persisted app state for a device (by MAC): auth token, cached sensor readings,
 * custom alarm titles, and widget → MAC bindings.
 */
object DeviceLocalDataCleaner {

    fun wipeAllLocalStateForDevice(context: Context, macAddress: String) {
        val mac = macAddress.normalizedBluetoothMac()
        if (mac.isEmpty()) return
        TokenStorage(context).removeToken(mac)
        SensorRepository.clearNamespaceForMac(context, mac)
        AlarmTitleRepository.clearNamespaceForMac(context, mac)
        WidgetPreferencesRepository(context).removeWidgetsBoundToMac(mac)
    }
}
