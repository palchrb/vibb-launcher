import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GitCommitValueSource : ValueSource<String, ValueSourceParameters.None> {

    @Inject
    abstract fun getExecOperations(): ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val action = object : Action<ExecSpec> {
            override fun execute(t: ExecSpec) {
                t.commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
                t.standardOutput = output
            }
        }
        getExecOperations().exec(action)
        return String(output.toByteArray(), Charset.defaultCharset()).trim()
    }
}

val gitCommitProvider = providers.of(GitCommitValueSource::class) {}
val gitCommit = gitCommitProvider.get()

/** The commit's time (ms) - a floor for the phone's clock: a wall clock earlier than the build is
 * unset (calls.BlockedCallLog doesn't prune then). Deterministic, unlike a build timestamp. */
abstract class GitCommitTimeValueSource : ValueSource<String, ValueSourceParameters.None> {

    @Inject
    abstract fun getExecOperations(): ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val action = object : Action<ExecSpec> {
            override fun execute(t: ExecSpec) {
                t.commandLine("git", "log", "-1", "--format=%ct", "HEAD")
                t.standardOutput = output
            }
        }
        getExecOperations().exec(action)
        return String(output.toByteArray(), Charset.defaultCharset()).trim()
    }
}

val gitCommitTimeMs = (providers.of(GitCommitTimeValueSource::class) {}.get().toLongOrNull() ?: 0L) * 1000L

val hasTsnet = file("libs/tsnet.aar").exists()
if (!hasTsnet) {
    if (providers.gradleProperty("requireTsnet").orNull == "true") {
        throw GradleException("libs/tsnet.aar is missing but -PrequireTsnet=true was given")
    }
    logger.warn("libs/tsnet.aar not found - building with the tsnet stub (no embedded tailnet)")
}

// Release signing. Each value comes from the env var first (CI), then from a Gradle property
// (local builds: put them in ~/.gradle/gradle.properties, never in this repo). Without a store
// file, assembleRelease still works and produces an unsigned APK, as before. PKCS12 keystores use
// one password for store and key, so the key password falls back to the store password.
fun releaseSecret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull
        ?.takeIf { it.isNotBlank() }
val releaseStoreFile = releaseSecret("ANDROID_RELEASE_KEYSTORE_FILE", "handy.release.storeFile")
if (releaseStoreFile == null && providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    throw GradleException(
        "-PrequireReleaseSigning=true, but no keystore is configured: set " +
            "ANDROID_RELEASE_KEYSTORE_FILE (or handy.release.storeFile) and the password/alias values"
    )
}

// Firebase Cloud Messaging (handy step 7, sync nudges). No google-services plugin and no
// google-services.json: the four values come from env vars (CI: repository variables) or Gradle
// properties (~/.gradle/gradle.properties, never this repo) and become BuildConfig fields. They
// are not secrets (they end up in the APK), but they are per project, so they stay out of the
// repo. Any value missing -> FCM is off in that build and the launcher uses the SSE stream only
// (upstream and local builds are unchanged). The debug variant (.debug package) is its own
// Firebase app, so it has its own application id; without one, debug builds have FCM off.
// -PrequireFcm=true (the tag release job) fails the build if the release config is incomplete.
val fcmProjectId = releaseSecret("HANDY_FCM_PROJECT_ID", "handy.fcm.projectId")
val fcmApplicationId = releaseSecret("HANDY_FCM_APPLICATION_ID", "handy.fcm.applicationId")
val fcmDebugApplicationId = releaseSecret("HANDY_FCM_DEBUG_APPLICATION_ID", "handy.fcm.debugApplicationId")
val fcmApiKey = releaseSecret("HANDY_FCM_API_KEY", "handy.fcm.apiKey")
val fcmSenderId = releaseSecret("HANDY_FCM_SENDER_ID", "handy.fcm.senderId")
val fcmBase = listOf(fcmProjectId, fcmApiKey, fcmSenderId).all { it != null }
val fcmRelease = fcmBase && fcmApplicationId != null
val fcmDebug = fcmBase && fcmDebugApplicationId != null
if (!fcmRelease && providers.gradleProperty("requireFcm").orNull == "true") {
    throw GradleException(
        "-PrequireFcm=true, but the FCM config is incomplete: set HANDY_FCM_PROJECT_ID, " +
            "HANDY_FCM_APPLICATION_ID, HANDY_FCM_API_KEY and HANDY_FCM_SENDER_ID " +
            "(or the handy.fcm.* Gradle properties)"
    )
}
if (!fcmRelease) logger.warn("No FCM config for release builds - push uses the SSE stream only")

/** A Kotlin/Java string literal for a BuildConfig field; the values are plain ids and keys. */
fun buildConfigString(value: String?): String {
    val v = value.orEmpty()
    require(v.all { it.isLetterOrDigit() || it in "-_:.@" }) { "Unexpected character in an FCM config value" }
    return "\"$v\""
}

// Release CI passes the version derived from the git tag (vMAJOR.MINOR.PATCH -> MAJOR*1_000_000 +
// MINOR*1_000 + PATCH, see .github/workflows/android.yml). Local and debug builds use the
// literals below - never bump those by hand for a release.
val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.let {
    it.toIntOrNull() ?: throw GradleException("-PversionCode must be an integer, got '$it'")
}
val versionNameOverride = providers.gradleProperty("versionName").orNull

android {
    namespace = "com.kidslauncher.mdm"
    compileSdk = 36

    defaultConfig {
        // The installed package (2026-10-06; was com.kidslauncher.mdm). The Kotlin namespace above
        // stays, so component class names are com.kidslauncher.mdm.* under package me.vibb.launcher
        // - e.g. the admin component me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver.
        applicationId = "me.vibb.launcher"
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeOverride ?: 116
        versionName = versionNameOverride ?: "0.23.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSecret("ANDROID_RELEASE_KEYSTORE_PASSWORD", "handy.release.storePassword")
                keyAlias = releaseSecret("ANDROID_RELEASE_KEY_ALIAS", "handy.release.keyAlias")
                keyPassword = releaseSecret("ANDROID_RELEASE_KEY_PASSWORD", "handy.release.keyPassword")
                    ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // No isDebuggable line: release is non-debuggable, which also blocks `adb shell run-as`.
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "FCM_APPLICATION_ID", buildConfigString(fcmApplicationId.takeIf { fcmRelease }))
            manifestPlaceholders["fcmEnabled"] = fcmRelease.toString()
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField("String", "FCM_APPLICATION_ID", buildConfigString(fcmDebugApplicationId.takeIf { fcmDebug }))
            manifestPlaceholders["fcmEnabled"] = fcmDebug.toString()
        }
    }

    defaultConfig {
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit}\"")
        buildConfigField("long", "GIT_COMMIT_TIME_MS", "${gitCommitTimeMs}L")
        buildConfigField("String", "FCM_PROJECT_ID", buildConfigString(fcmProjectId))
        buildConfigField("String", "FCM_API_KEY", buildConfigString(fcmApiKey))
        buildConfigField("String", "FCM_SENDER_ID", buildConfigString(fcmSenderId))
    }

    // libs/tsnet.aar is built by CI (Go + NDK, x86_64 only). Without it, compile a stub of its
    // API instead so the app still builds and unit-tests locally; the stub fails every tailnet
    // connect. CI passes -PrequireTsnet=true so a release can never ship the stub by accident.
    if (!hasTsnet) {
        sourceSets.getByName("main").java.srcDir("src/tsnetStub/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // CI passes -PwarningsAsErrors=true (.github/workflows/launcher.yml): the build log has
        // no Kotlin warnings, and a new one fails the build there (local builds only warn).
        allWarningsAsErrors = providers.gradleProperty("warningsAsErrors").orNull == "true"
    }
    buildFeatures {
        buildConfig = true
        compose = false
        dataBinding = true
        viewBinding = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE-notice.md"
            )
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Built by CI (see .github/workflows/android.yml's "Build tsnet.aar with
    // gomobile" step, and mobile/go.mod) - not checked in, since it's a
    // multi-hundred-MB Go-toolchain build artifact. Gives the launcher its
    // own embeddable tailnet connection - see CLAUDE.md.
    if (hasTsnet) {
        implementation(files("libs/tsnet.aar"))
    }
    // IP/UDP packet parsing+construction (with automatic checksum/length
    // correction) and DNS message parsing, for KidVpnService's local packet
    // filter - same libraries (and versions, for pcap4j) DNS66 uses for this
    // exact pattern on Android, confirmed via its own build.gradle rather
    // than assumed. MIT (pcap4j) and BSD-3-Clause (dnsjava), both compatible
    // with this repo's GPLv3.
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation("dnsjava:dnsjava:3.6.5")
    // In-app "Scan setup QR" flow (SettingsFragmentLauncher) - ZXing, not Google's ML Kit, to
    // match this project's existing avoid-Google/Play-Services-dependencies pattern (embedded
    // tsnet over the standalone Tailscale app, etc.). Zero GMS footprint.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Sync nudges over FCM (handy step 7, design 07-battery-fcm-play.md). Messaging only - no
    // analytics, no google-services plugin; initialised by hand (FcmSupport) and only when the
    // build has a config. Without one, nothing of it runs and the SSE stream is the transport.
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.squareup.retrofit)
    implementation(libs.jakewharton.retrofit2.kotlinx.serialization.converter)
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.sse)
    implementation(libs.jonahbauer.android.preference.annotations)
    kapt(libs.jonahbauer.android.preference.annotations)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
