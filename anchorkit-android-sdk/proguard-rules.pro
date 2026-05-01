# AnchorKit SDK ProGuard rules

# Keep public SDK surface so consuming apps can call it
-keep class io.anchorkit.sdk.AnchorKit { *; }
-keep class io.anchorkit.sdk.AnchorKitClient { *; }
-keep class io.anchorkit.sdk.AnchorKitError { *; }
-keep class io.anchorkit.sdk.AnchorKitError$* { *; }
-keep class io.anchorkit.sdk.EnclaveAttestation { *; }
-keep class io.anchorkit.sdk.EnclaveAttestation$* { *; }
-keep class io.anchorkit.sdk.SolanaVerifier { *; }

# Keep serializable data models (kotlinx.serialization reads field names at runtime)
-keep,includedescriptorclasses class io.anchorkit.sdk.models.** { *; }
-keep,includedescriptorclasses class io.anchorkit.sdk.AttestationChallenge { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }

# BouncyCastle (Ed25519 point validation for Solana PDA derivation)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
