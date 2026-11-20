plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment, so nothing secret is in the repo. GitHub Actions
// sets it from the repo's secrets (.github/workflows/release.yml); a local build without it
// still works and makes a debug APK as usual.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseKeystore = env("LUNA_KEYSTORE")?.let { file(it) }

android {
    namespace = "imbored.luna.station"
    // Compose 1.12 needs API 37 to compile against. Android Studio offers to
    // install the platform on the first sync if it's missing.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "imbored.luna.station"
        minSdk = 30            // Android 11 = Fire OS 8, the main target
        targetSdk = 36
        // The version lives here, not in CI, so F-Droid (which builds the app itself) sees it too.
        // publish-github.sh --tag vX.Y.Z sets both: versionCode = X*10000 + Y*100 + Z (0.6.2 -> 602),
        // above every build GitHub made before (those used the Actions run number).
        versionCode = 602
        versionName = "0.6.2"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("LUNA_KEYSTORE_PASSWORD")
                keyAlias = env("LUNA_KEY_ALIAS")
                keyPassword = env("LUNA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // isMinifyEnabled = true        // (the F-Droid PR's switch; AGP 9 has optimization {} for it)
            // isShrinkResources = true
            // R8: shrinks the APK (about 30 MB before). Test a release build after changing anything
            // here; keep rules for anything it strips by mistake go in src/main/keepRules/.
            optimization {
                enable = true
            }
            // only when the release key is there; without it the release APK is unsigned
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
