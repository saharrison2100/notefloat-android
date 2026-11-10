import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Signing values come from keystore.properties (git-ignored) or environment variables.
// Nothing secret is ever committed. See documentation/BUILD_INSTRUCTIONS.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.notefloat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notefloat.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "1.2.0"
        // Base44 backend base URL. Public value, not a secret.
        buildConfigField("String", "API_BASE_URL", "\"https://neo-58a47037.base44.app/functions/\"")
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        val path = signing("storeFile", "NF_STORE_FILE")
        if (path != null) {
            create("release") {
                storeFile = file(path)
                storePassword = signing("storePassword", "NF_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "NF_KEY_ALIAS")
                keyPassword = signing("keyPassword", "NF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; viewBinding = false }
    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM, so the snapshot codec can be unit tested off-device.
    testImplementation("org.json:json:20240303")
}
