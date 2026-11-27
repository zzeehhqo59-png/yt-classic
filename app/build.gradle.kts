import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing: CI passes YTCLASSIC_* env vars (keystore decoded from GitHub Secrets into the runner temp dir);
// local builds fall back to signing.properties outside the repo. Never commit the key or passwords.
val localSigning = Properties().apply {
    file("/workspace/yt-classic/keys/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localSigning.getProperty(prop)?.takeIf { it.isNotBlank() }
val keystorePath = signingValue("YTCLASSIC_KEYSTORE_PATH", "storeFile")
val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.bear.ytclassic"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.bear.ytclassic"
        minSdk = 23
        targetSdk = 35
        versionCode = 7004
        versionName = "6.0.4"
    }

    if (keystoreFile != null) {
        signingConfigs {
            create("ytclassic") {
                storeFile = keystoreFile
                storePassword = signingValue("YTCLASSIC_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("YTCLASSIC_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("YTCLASSIC_KEY_PASSWORD", "keyPassword")
            }
        }
    }
    val appSigning = signingConfigs.findByName("ytclassic")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (appSigning != null) signingConfig = appSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            if (appSigning != null) signingConfig = appSigning
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = false }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}


dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt.coil3:coil:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-dash:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
    implementation("com.google.android.exoplayer:extension-mediasession:2.19.1")
}
