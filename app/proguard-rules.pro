# MyHealth R8 rules (PLAN P8.7).
#
# The release build is minified + resource-shrunk and signed with the debug keystore (personal-use
# sideload, see app/build.gradle.kts). Everything below is a keep that R8 cannot infer on its own
# because the reference is reflective: kotlinx-serialization's generated serializers, Room's
# generated implementations, the Garmin FIT profile tables, ML Kit's Play-Services modules,
# Health Connect's client, and the WorkManager workers instantiated by name from the JobScheduler.
#
# Rule of thumb when a release-only crash appears: `ClassNotFoundException` /
# `NoSuchMethodException` / `SerializationException: Serializer for class 'X' is not found`
# all mean "add a keep here", never "turn minification off".

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin / coroutines -------------------------------------------------------------------

-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
# Kotlin's intrinsics throw with the parameter name; keeping them keeps stack traces readable.
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---- kotlinx.serialization ------------------------------------------------------------------
# The compiler plugin generates a `$$serializer` object and a `Companion.serializer()` per
# `@Serializable` class; both are only ever reached reflectively from `serializer<T>()`.

-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.myhealth.**$$serializer { *; }
-keep class com.myhealth.**$Companion { *; }
# Every `@Serializable` model in the app: backup files, Health Connect DTOs, FIT records, Open
# Food Facts DTOs, the navigation routes and the Room entities that carry JSON columns.
-keep @kotlinx.serialization.Serializable class com.myhealth.** { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---- Navigation-Compose type-safe routes ----------------------------------------------------
# `@Serializable` route objects are matched by their fully-qualified name at runtime.

-keep class com.myhealth.ui.nav.** { *; }

# ---- Room ------------------------------------------------------------------------------------
# `Room.databaseBuilder` loads `MyHealthDatabase_Impl` by name; DAOs and entities are constructed
# and read by the generated code, and the converters are resolved reflectively.

-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.myhealth.data.db.MyHealthDatabase_Impl { *; }
-keep class com.myhealth.data.db.entity.** { *; }
-keep class com.myhealth.data.db.converter.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---- DataStore Preferences --------------------------------------------------------------------
# The preferences proto is parsed through generated protobuf classes that use reflection.

-keep class androidx.datastore.*.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ---- WorkManager -------------------------------------------------------------------------------
# Workers are re-created by class name after a reboot, through the two-arg
# `(Context, WorkerParameters)` constructor.

-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.myhealth.sync.** { *; }
-keep class * extends androidx.startup.Initializer { *; }

# ---- Health Connect ------------------------------------------------------------------------------
# The client is resolved through the Health Connect APK's AIDL surface; records and permission
# contracts are matched by class.

-keep class androidx.health.connect.client.** { *; }
-keep class androidx.health.platform.client.** { *; }
-dontwarn androidx.health.connect.client.**
-dontwarn androidx.health.platform.client.**

# ---- ML Kit / Play Services (amendment A3: unbundled text + barcode) --------------------------
# The models live in Play Services and are loaded dynamically; the optional bundled artifacts are
# deliberately absent, hence the `-dontwarn`s.

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.annotation.** { *; }
-keepclassmembers class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- CameraX -------------------------------------------------------------------------------------

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- OkHttp (Ktor engine of the Open Food Facts client, P4.10/P20.2) ------------------------------

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Compose ---------------------------------------------------------------------------------------
# Previews and tooling are debug-only; the runtime itself ships its own consumer rules.

-dontwarn androidx.compose.ui.tooling.**
