package io.anchorkit.sdk

import java.security.MessageDigest

object HashUtils {
    /**
     * Hash photo data using SHA-256
     * @param photoData ByteArray of the photo
     * @return Hex string of the hash
     */
    fun hashPhoto(photoData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(photoData)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Hash a file
     * @param file File to hash
     * @return Hex string of the hash
     */
    fun hashFile(file: java.io.File): String {
        val bytes = file.readBytes()
        return hashPhoto(bytes)
    }
}