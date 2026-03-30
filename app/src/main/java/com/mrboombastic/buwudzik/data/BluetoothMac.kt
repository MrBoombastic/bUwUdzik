package com.mrboombastic.buwudzik.data

import java.util.Locale

/** Normalizes a Bluetooth MAC for storage and comparison (stable across device locales). */
fun String.normalizedBluetoothMac(): String = trim().uppercase(Locale.ROOT)
