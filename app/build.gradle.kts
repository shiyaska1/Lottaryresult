plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// versionCode = number of commits in the git history, so it rises on every commit with no
// hand-editing and is always recoverable from git itself (`git rev-list --count <commit>`
// reproduces the exact versionCode that commit was built with). VERSION_CODE can still override
// it (e.g. a CI step that already computed it) but by default every build - CI or local -
// derives the same number from the same commit, so the app version and the git history are one
// and the same source of truth. Needs full git history (not a shallow clone) to be accurate.
fun gitCommitCount(): Int {
    return try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

android {
    namespace = "com.keralalottery.print"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keralalottery.print"
        minSdk = 29
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: gitCommitCount()
        versionName = "1.7.3"
        vectorDrawables { useSupportLibrary = true }
    }

    // Play Store upload key — provided via env vars for CI/release builds (kept out of git,
    // see /secrets/). Falls back to the committed debug key so a plain ./gradlew still works.
    val uploadStoreFile = System.getenv("UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
    val hasUploadKey = !uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank()

    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = "lottery123"
            keyAlias = "lotteryprint"
            keyPassword = "lottery123"
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "stable")
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
    }
    packaging {
        // AGP defaults to storing dex uncompressed once minSdk >= 28 (faster install, much
        // bigger APK file). This app is a small side-loaded utility handed out as a direct
        // download, so a compact file matters more than shaving install time.
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Extracts the text layer from the official Kerala Lotteries PDF (position-sorted, so
    // rows/columns come out in reading order instead of internal PDF draw order).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Fetches the results listing + latest-draw PDF from the official government portal.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Play in-app updates: forces an update as soon as one is published (see AppUpdater).
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Local database for Calculator's saved tapes and the Diary tab's entries/attachments.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
