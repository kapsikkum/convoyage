# R8 rules for release builds (isMinifyEnabled = true).

# ---- kotlinx.serialization ---------------------------------------------------------------------
# The library ships consumer rules for its own classes; these keep OUR @Serializable models intact, with their
# generated $$serializer classes, Companion.serializer() lookups and the field names that are the wire format.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, EnclosingMethod
-dontnote kotlinx.serialization.**

# WebSocket messages and the shared Hazard type.
-keep class app.convoyage.protocol.** { *; }
-keepclassmembers class app.convoyage.protocol.** { *** Companion; }
# REST DTOs (app.convoyage.api: MagicLinkRequest, TokenPair, Me, CreateHazard, ...).
-keep class app.convoyage.api.** { *; }
-keepclassmembers class app.convoyage.api.** { *** Companion; }

-keepclassmembers @kotlinx.serialization.Serializable class app.convoyage.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.convoyage.**$$serializer { *; }

# ---- OkHttp / Okio -----------------------------------------------------------------------------
# OkHttp 5 and Okio bundle their own consumer rules; these only silence optional-platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- MapLibre ----------------------------------------------------------------------------------
# The native library calls back into Java by name, and the SDK ships consumer rules for that; keep the public
# API too so the style/layer classes we use from Kotlin cannot be renamed under it.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
