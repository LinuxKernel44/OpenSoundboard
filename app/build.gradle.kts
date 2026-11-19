import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: read from keystore.properties (never committed). Path can be overridden with
// -PkeystoreProperties=/path/to/file or the OSB_KEYSTORE_PROPERTIES environment variable.
val keystoreProps = Properties().apply {
    val path = (project.findProperty("keystoreProperties") as String?)
        ?: System.getenv("OSB_KEYSTORE_PROPERTIES")
    val f = if (path != null) file(path) else rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.linuxkernel44.opensoundboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.linuxkernel44.opensoundboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.12.2"
        // yt-dlp + ffmpeg make each ABI about 55 MB, so only arm64 phones and the x86_64 emulator are built (-PosbAbis=arm64-v8a for a release).
        ndk { abiFilters += ((project.findProperty("osbAbis") as String?) ?: "arm64-v8a,x86_64").split(",") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fno-exceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
    // yt-dlp unzips its bundled Python and ffmpeg from the native library folder, so the libraries must be extracted at install.
    packaging { jniLibs { keepDebugSymbols += "**/*.so"; useLegacyPackaging = true } }
    androidResources { localeFilters += listOf("en", "fr") }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    lint { abortOnError = false }
}

dependencies {
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-graphics:1.12.0")
    implementation("androidx.compose.material:material-ripple:1.12.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.oboe:oboe:1.10.0")
    // Link downloads: yt-dlp (bundled Python + ffmpeg) first, NewPipeExtractor as fallback.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
