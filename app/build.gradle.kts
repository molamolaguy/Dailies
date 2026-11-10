import java.util.Properties

plugins {
    id("com.android.application")
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.11"
}

val localProps = Properties().apply {
    val lpFile = rootProject.file("local.properties")
    if (lpFile.exists()) {
        lpFile.inputStream().use { load(it) }
    }
}

val allowDebugSignedRelease = (findProperty("allowDebugSignedRelease") as String?).toBoolean()

android {
    namespace = "com.dailygames.hub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dailygames.hub"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_STORE_FILE") ?: localProps.getProperty("release.storeFile")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: localProps.getProperty("release.storePassword")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: localProps.getProperty("release.keyAlias")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = when {
                releaseConfig.storeFile != null -> releaseConfig
                // Explicit opt-in only (e.g. -PallowDebugSignedRelease=true for local testing).
                allowDebugSignedRelease -> signingConfigs.getByName("debug")
                else -> null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

base {
    archivesName.set("Dailies")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // QR codes for shared game lists (Apache-2.0).
    implementation("com.google.zxing:core:3.5.4")
    // Reading results from screenshots (on-device text recognition through Google Play services).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")

    // On-device smoke tests (./gradlew connectedDebugAndroidTest; run on an emulator in CI).
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Never ship a release build signed with a debug key by accident: fail fast when no release keystore
// is configured (set RELEASE_STORE_FILE etc. or release.* in local.properties).
afterEvaluate {
    val releaseSigning = android.signingConfigs.getByName("release")
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst {
            if (releaseSigning.storeFile == null && !allowDebugSignedRelease) {
                throw GradleException(
                    "No release keystore configured. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD (or release.* in local.properties), " +
                        "or pass -PallowDebugSignedRelease=true for a local test build."
                )
            }
        }
    }
}

// Unit tests for the hub's JS logic (app/src/main/assets/hub-core.js). Requires Node.js 18+ on PATH:
//   ./gradlew testHubJs
tasks.register<Exec>("testHubJs") {
    group = "verification"
    description = "Runs the Node unit tests for hub-core.js against the shared schedule fixtures."
    workingDir = rootProject.projectDir
    commandLine("node", "--test", "app/src/test/js/hub-core.test.js", "app/src/test/js/known-games.test.js")
}
