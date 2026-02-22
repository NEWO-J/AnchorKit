package io.framechain.sdk

import android.os.Build
import java.io.File

/**
 * Best-effort client-side device integrity checks.
 *
 * These checks detect common indicators of a rooted device or an unlocked
 * bootloader and are performed at the start of [Framechain.captureAndSubmit]
 * so that users get an actionable error immediately rather than a cryptic
 * server rejection.
 *
 * IMPORTANT — trust model
 * -----------------------
 * These checks are defense-in-depth only. A sufficiently motivated attacker
 * with full root access can bypass every one of them (hiding binaries, hooking
 * Build.TAGS, etc.). The authoritative, hardware-enforced gate is the
 * server-side Android Keystore attestation check: `verifiedBootState = Verified`
 * is encoded in a certificate signed inside the Trusted Execution Environment
 * and cannot be forged without compromising the device's secure hardware.
 *
 * Keeping the checks here is still worthwhile because:
 *   1. They fail fast with a meaningful error before any network round-trip.
 *   2. They raise the bar — casual/script-kiddie root setups are caught here.
 *   3. They make the security intent explicit in the SDK.
 */
internal object DeviceIntegrity {

    // Common paths present on rooted devices or when Magisk / su is installed.
    private val ROOT_INDICATORS = listOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/xbin/busybox",
        "/data/adb/magisk.db",      // Magisk database
        "/sbin/magisk",             // Magisk binary
        "/data/adb/magisk",         // Magisk directory
    )

    /**
     * Run all integrity checks and return a human-readable description of
     * the first problem found, or null if the device appears clean.
     *
     * Called from [Framechain.captureAndSubmit] before any camera or network
     * work is performed.
     */
    internal fun check(): String? {
        // 1. Build tags — "test-keys" indicates a non-production build with an
        //    unlocked bootloader or an unofficial / custom ROM.
        val buildTags = Build.TAGS ?: ""
        if (buildTags.contains("test-keys")) {
            return "Device build is signed with test-keys (unlocked bootloader or unofficial ROM detected)"
        }

        // 2. Check for well-known root binaries and Magisk artefacts.
        for (path in ROOT_INDICATORS) {
            if (File(path).exists()) {
                return "Potential root access detected — device may be compromised"
            }
        }

        // 3. Verify the system partition is not writable.
        //    On stock Android, /system is always mounted read-only.
        //    A writable /system is a strong indicator of a rooted device.
        try {
            val systemDir = File("/system")
            if (systemDir.exists() && systemDir.canWrite()) {
                return "/system partition is writable — device may be rooted"
            }
        } catch (_: Exception) {
            // SecurityException or similar — assume clean
        }

        return null // Device appears to be unmodified
    }
}
