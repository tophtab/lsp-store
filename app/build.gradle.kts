plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The application id is what the Xposed module repository keys everything on:
    // repo name, module identity and the update chain all follow it, so it cannot
    // change after the first published release.
    namespace = "io.github.tophtab.lspstore"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.tophtab.lspstore"
        // LSPosed itself supports Android 8.1 (API 27) and up.
        minSdk = 27
        targetSdk = 36
        // versionCode = major * 10000 + minor * 100 + patch, so 1.0.0 is 10000,
        // 1.1.0 is 10100 and 12.0.0 is 120000. Android compares the integer, so a
        // wider major just means more digits; there is nothing to zero-pad.
        // minor and patch must stay below 100 for this to remain monotonic.
        versionCode = 10000
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release APKs are self-signed: the module repository serves them as-is,
        // there is no store that re-signs on our behalf. The keystore is supplied
        // by CI through the environment, never committed.
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (!keystoreFile.isNullOrEmpty()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without KEYSTORE_FILE the APK stays unsigned (CI uses that to compile-
            // check release builds); publishing jobs verify the signature separately.
            signingConfig = if (System.getenv("KEYSTORE_FILE").isNullOrEmpty()) {
                null
            } else {
                signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // LSPosed reads the module declaration from the APK itself; keep the
        // resource merger from dropping META-INF/xposed/*.
        resources.merges += "META-INF/xposed/*"
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // A missing or stale translation fails the build instead of silently
        // shipping English text in a localized UI.
        error += setOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    // Compile-time only: the framework provides this API at runtime, so no code
    // from it is packaged into the APK.
    compileOnly(libs.libxposed.api)
    // Ships the `io.github.libxposed.service.XposedProvider` provider through the
    // manifest merge. That provider is how Xposed tooling - this store's own
    // "installed modules" scan included - recognises a modern module, so without
    // it the store cannot see (and therefore cannot update) itself.
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
