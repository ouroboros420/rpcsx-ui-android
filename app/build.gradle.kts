plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

// Build identity, consistent with the core (.so): calendar date + commit time
// of this repo's HEAD, e.g. "2026.06.06-1430". Ordered and readable so testers
// can always tell which build is newer; no git hash, no v1.x semver.
fun gitDateTime(dir: java.io.File): String = try {
    val proc = ProcessBuilder("git", "log", "-1", "--date=format:%Y.%m.%d-%H%M", "--format=%cd")
        .directory(dir).redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().use { it.readText() }.trim()
    proc.waitFor()
    text.ifEmpty { "0000.00.00-0000" }
} catch (e: Exception) {
    "0000.00.00-0000"
}

fun gitDateCode(dir: java.io.File): Int = try {
    val proc = ProcessBuilder("git", "log", "-1", "--date=format:%Y%m%d", "--format=%cd")
        .directory(dir).redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().use { it.readText() }.trim()
    proc.waitFor()
    text.toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

android {
    namespace = "net.rpcsx"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        // Distinct from official RPCSX (net.rpcsx) so this fork installs
        // side-by-side instead of overwriting it. The Kotlin namespace stays
        // net.rpcsx; only the installed package identity changes here.
        applicationId = "net.rpcsx.clanker"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("RX_VERSION_CODE")?.toIntOrNull() ?: gitDateCode(rootDir)
        versionName = System.getenv("RX_VERSION") ?: gitDateTime(rootDir)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "Version", "\"v${versionName}\"")
    }

    signingConfigs {
        val keystoreAlias = System.getenv("KEYSTORE_ALIAS") ?: ""
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""

        if (keystorePath.isNotEmpty() && file(keystorePath).exists() && file(keystorePath).length() > 0) {
            create("custom-key") {
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                storeFile = file(keystorePath)
                storePassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("custom-key") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }
}

base.archivesName = "rpcsx"

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.squareup.okhttp3)
    implementation(libs.androidx.documentfile)
    implementation(libs.materialswitch)
}
