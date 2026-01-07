package com.mrboombastic.buwudzik.device

import java.util.Locale

data class Alarm(
    val id: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Int,  // Bitmask: 0x01=Mon, 0x02=Tue, 0x04=Wed, 0x08=Thu, 0x10=Fri, 0x20=Sat, 0x40=Sun, 0x00=Once
    val snooze: Boolean,
    val title: String = ""  // Optional user-defined title, stored locally
) {

    fun getTimeString(): String {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    }
}

