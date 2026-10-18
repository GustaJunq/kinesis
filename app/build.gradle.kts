import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.kinesis.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.gustajunq.kinesis"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.6-hotfix"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The native libraries (ours and MediaPipe's) are built for these ABIs only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Instrumented tests render and export the shipped example projects.
    sourceSets.getByName("androidTest").assets.srcDir("../.claude/skills/knsproj/examples")

    // Release signing. The credentials never live in the repository: CI restores the keystore from a secret and
    // passes everything through environment variables (KINESIS_KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
    val keystorePath = System.getenv("KINESIS_KEYSTORE_FILE")
    val hasReleaseKey = !keystorePath.isNullOrEmpty() && file(keystorePath).isFile &&
        listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !System.getenv(it).isNullOrEmpty() }
    // The release workflow sets this so a build can never silently fall back to the debug key.
    val requireReleaseKey = System.getenv("REQUIRE_RELEASE_SIGNING") == "true"

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                hasReleaseKey -> signingConfigs.getByName("release")
                requireReleaseKey -> throw GradleException(
                    "REQUIRE_RELEASE_SIGNING=true but the release keystore or one of KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD is missing",
                )
                // Local builds without the production key: signed with the debug key so the APK still installs.
                // Such an APK is for testing only and must never be published (the release workflow refuses it).
                else -> signingConfigs.getByName("debug")
            }
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
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    // Reproducible builds: no build timestamps or VCS info baked into the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // On-device person segmentation (Apache-2.0) for background removal; the model is in assets/models.
    implementation(libs.mediapipe.tasks.vision)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
