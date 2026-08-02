import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Backend base URL, resolved at configure time.
 *
 * The emulator alias 10.0.2.2 only works inside an emulator. For a demo on a
 * physical phone the app has to reach the dev machine over the LAN, so the URL
 * is read from `local.properties` (which is per-developer and not committed):
 *
 *     taskflow.baseUrl=http://192.168.1.42:8080/
 *
 * With no entry, the emulator default is used and nothing needs configuring.
 * See README.md — the matching LAN IP must also be added to
 * res/xml/network_security_config.xml, since cleartext HTTP is allow-listed
 * per-domain.
 */
/** Everything machine-local: SDK path, backend URL, release signing. Never committed. */
val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val taskflowBaseUrl: String = run {
    val configured = localProperties.getProperty("taskflow.baseUrl")?.trim()
    val raw = if (configured.isNullOrEmpty()) "http://10.0.2.2:8080/" else configured
    // Retrofit requires a base URL ending in '/'.
    if (raw.endsWith("/")) raw else "$raw/"
}

/**
 * Release keystore, if one has been configured.
 *
 * Kept in local.properties (gitignored) so the keystore path and passwords
 * never reach the repository. When it isn't configured the release build still
 * works — it just produces an *unsigned* APK, which Android cannot install.
 * See README.md for how to create one.
 */
val releaseKeystore: File? = localProperties.getProperty("taskflow.keystore")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.taskflow.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taskflow.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BASE_URL", "\"$taskflowBaseUrl\"")
    }

    signingConfigs {
        // Only declared when a keystore is actually configured, so the build
        // still works on a fresh clone that has none.
        releaseKeystore?.let { keystore ->
            create("release") {
                storeFile = keystore
                storePassword = localProperties.getProperty("taskflow.keystorePassword")
                keyAlias = localProperties.getProperty("taskflow.keyAlias")
                keyPassword = localProperties.getProperty("taskflow.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Unsigned without a configured keystore — Android will refuse to
            // install that APK. See README.md.
            signingConfig = signingConfigs.findByName("release")

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Required for BuildConfig.BASE_URL and BuildConfig.DEBUG.
        buildConfig = true
    }
}

dependencies {
    // Compose BOM — single version source for all Compose libraries.
    // 2024.09.00 brings Material 3 1.3.0, which is where the stable
    // pull-to-refresh API (androidx.compose.material3.pulltorefresh) lives.
    // Still Kotlin 2.0 / AGP 8.4 / minSdk 26 compatible.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel + Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    // Lifecycle-aware Compose helpers (LocalLifecycleOwner lives here now —
    // the compose-ui copy is deprecated as of Compose 1.7). Used by
    // PollWhileResumed to stop polling when the app is backgrounded.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Plain JVM unit tests (UpdateTaskRequestTypeAdapterTest) — no Android deps.
    testImplementation("junit:junit:4.13.2")
}
