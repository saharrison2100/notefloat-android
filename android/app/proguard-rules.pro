-keep class com.notefloat.app.storage.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# Annotations referenced by Tink (via androidx.security:security-crypto) that are
# compile-time only and absent from the runtime classpath.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**

# Tink loads primitives reflectively through its registry.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.crypto.tink.**

# Kotlin metadata used by Room/coroutines generated code.
-keepclassmembers class kotlin.Metadata { public <methods>; }
