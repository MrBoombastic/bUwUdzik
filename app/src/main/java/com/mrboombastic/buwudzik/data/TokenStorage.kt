package com.mrboombastic.buwudzik.data


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.mrboombastic.buwudzik.utils.AppLogger
import java.security.SecureRandom

/**
 * Manages auth tokens for paired QP devices.
 * Each device (by MAC address) gets its own unique 16-byte token.
 * Token is generated randomly during first pairing and stored for future use.
 */
class TokenStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "QP_tokens"
        private const val TAG = "TokenStorage"
        private const val TOKEN_SIZE = 16
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    /**
     * Get the stored token for a device, or null if not paired yet.
     */
    fun getToken(macAddress: String): ByteArray? {
        val key = macAddressToKey(macAddress)
        val tokenHex = prefs.getString(key, null) ?: return null
        return try {
            hexToBytes(tokenHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored token for $macAddress", e)
            null
        }
    }

    /**
     * Get the stored token as hex string, or null if not paired yet.
     */
    fun getTokenHex(macAddress: String): String? {
        val key = macAddressToKey(macAddress)
        return prefs.getString(key, null)
    }

    /**
     * Generate a new random token without storing it.
     * Use storeToken after pairing is confirmed.
     */
    fun generateToken(): ByteArray {
        val token = ByteArray(TOKEN_SIZE)
        secureRandom.nextBytes(token)
        AppLogger.d(TAG, "Generated new token: ${bytesToHex(token)}")
        return token
    }

    /**
     * Store a token for a device after successful pairing.
     */
    fun storeToken(macAddress: String, token: ByteArray) {
        val key = macAddressToKey(macAddress)
        val tokenHex = bytesToHex(token)
        prefs.edit { putString(key, tokenHex) }
        AppLogger.d(TAG, "Stored token for $macAddress: $tokenHex")
    }

    /**
     * Check if a device has been paired (has stored token).
     */
    fun isPaired(macAddress: String): Boolean {
        val key = macAddressToKey(macAddress)
        return prefs.contains(key)
    }

    /**
     * Remove stored token for a device (unpair).
     */
    fun removeToken(macAddress: String) {
        val key = macAddressToKey(macAddress)
        prefs.edit { remove(key) }
        AppLogger.d(TAG, "Removed token for $macAddress")
    }

    private fun macAddressToKey(macAddress: String): String {
        // Convert MAC to safe key: "58:2D:34:50:A0:81" -> "token_58_2d_34_50_a0_81"
        return "token_${macAddress.lowercase().replace(":", "_")}"
    }

    /**
     * Convert bytes to hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Convert hex string to bytes.
     */
    fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}


