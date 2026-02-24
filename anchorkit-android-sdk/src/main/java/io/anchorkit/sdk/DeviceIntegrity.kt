package io.anchorkit.sdk

import android.os.Build
import java.io.File

/**
 * Best-effort client-side device integrity checks.
 *
 * These checks detect common indicators of a rooted device or an unlocked
 * bootloader and are performed at the start of [AnchorKit.captureAndSubmit]
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

    // Build property substrings that identify known Android emulators.
    // Emulators produce software-only Keystore attestation (securityLevel=Software)
    // which the server rejects.  Catching emulators here surfaces a clear error
    // message before the round-trip to the server.
    private val EMULATOR_FINGERPRINT_TOKENS = listOf(
        "generic", "unknown", "google_sdk", "Emulator", "Android SDK built for x86"
    )

    /**
     * Run all integrity checks and return a human-readable description of
     * the first problem found, or null if the device appears clean.
     *
     * Called from [AnchorKit.captureAndSubmit] before any camera or network
     * work is performed.
     */
    internal fun check(): String? {
        // 1. Build tags — "test-keys" indicates a non-production build with an
        //    unlocked bootloader or an unofficial / custom ROM.
        val buildTags = Build.TAGS ?: ""
        if (buildTags.contains("test-keys")) {
            return "Device build is signed with test-keys (unlocked bootloader or unofficial ROM detected)"
        }

        // 2. Emulator detection.
        //    Emulators run on virtual hardware and produce software-level attestation,
        //    which the server always rejects.  Detecting them client-side gives a
        //    descriptive error rather than a cryptic 403.
        //
        //    Checks (any one is sufficient to flag as emulator):
        //      a. Build.FINGERPRINT contains a known emulator token.
        //      b. Build.HARDWARE is the goldfish or ranchu virtual kernel.
        //      c. Build.PRODUCT contains "sdk" / "gphone" / "vbox".
        //      d. Build.MODEL matches an emulator model string.
        //      e. Build.MANUFACTURER is "Genymotion".
        val fingerprint = Build.FINGERPRINT ?: ""
        if (EMULATOR_FINGERPRINT_TOKENS.any { fingerprint.contains(it, ignoreCase = true) }) {
            return "Device appears to be an emulator (FINGERPRINT contains emulator token)"
        }

        val hardware = (Build.HARDWARE ?: "").lowercase()
        if (hardware == "goldfish" || hardware == "ranchu") {
            return "Device appears to be an emulator (HARDWARE=$hardware)"
        }

        val product = (Build.PRODUCT ?: "").lowercase()
        if (product.contains("sdk") || product.contains("gphone") || product.contains("vbox")) {
            return "Device appears to be an emulator (PRODUCT=$product)"
        }

        val model = Build.MODEL ?: ""
        if (model.contains("sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK", ignoreCase = true)
        ) {
            return "Device appears to be an emulator (MODEL=$model)"
        }

        if ((Build.MANUFACTURER ?: "").equals("Genymotion", ignoreCase = true)) {
            return "Device appears to be a Genymotion emulator"
        }

        // 3. Check for well-known root binaries and Magisk artefacts.
        for (path in ROOT_INDICATORS) {
            if (File(path).exists()) {
                return "Potential root access detected — device may be compromised"
            }
        }

        // 4. Verify the system partition is not writable.
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
