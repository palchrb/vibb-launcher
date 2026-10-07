import com.android.build.api.artifact.SingleArtifact
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

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
        }
    }

    defaultConfig {
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit}\"")
        buildConfigField("long", "GIT_COMMIT_TIME_MS", "${gitCommitTimeMs}L")
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

/**
 * The design 16d lock-task override hook lives in src/debug only (docs/testing/emulator.md §6e).
 * This scans a variant's merged manifest and its APKs' dex for the hook's names: a release build
 * fails if any is there ([expectPresent] false, a dependency of assembleRelease), and the debug
 * build checks they are there (a positive control: the scan really finds them).
 */
abstract class CheckDebugHook : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:InputDirectory
    abstract val apkDir: DirectoryProperty

    @get:Input
    abstract val names: ListProperty<String>

    @get:Input
    abstract val expectPresent: Property<Boolean>

    @TaskAction
    fun check() {
        val found = mutableSetOf<String>()
        val manifestText = manifest.get().asFile.readText()
        names.get().filterTo(found) { manifestText.contains(it) }
        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        if (apks.isEmpty()) throw GradleException("No APK in ${apkDir.get().asFile}")
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.endsWith(".dex") }) {
                    // Dex strings (class descriptors, Kotlin metadata, literals) are MUTF-8: ASCII names match as bytes.
                    val dex = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                    names.get().filterTo(found) { dex.contains(it) }
                }
            }
        }
        if (expectPresent.get()) {
            val missing = names.get() - found
            if (missing.isNotEmpty()) throw GradleException("Debug build lacks the 16d hook ($missing) - the release check would prove nothing")
        } else if (found.isNotEmpty()) {
            throw GradleException("The debug-only lock-task hook is in a release build: $found (keep it in src/debug)")
        }
        logger.lifecycle("${name}: ${if (expectPresent.get()) "hook present" else "no debug hook"} in ${apks.map { it.name }}")
    }
}

/**
 * Design 19: FCM is gone, and nothing of Firebase, Play services or Google's data transport may
 * come back into the release APK through a dependency (one APK works with anyone's server, and no
 * nudge metadata goes to Google). Walks the release runtime classpath's resolved graph and fails
 * on any module in [bannedGroups] (the group itself or a subgroup). A dependency of
 * assembleRelease.
 */
abstract class CheckNoGoogleServices : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:Input
    abstract val bannedGroups: ListProperty<String>

    @TaskAction
    fun check() {
        val seen = mutableSetOf<ComponentIdentifier>()
        val modules = sortedSetOf<String>()
        val queue = mutableListOf<ResolvedComponentResult>(rootComponent.get())
        while (queue.isNotEmpty()) {
            val component = queue.removeAt(queue.lastIndex)
            if (!seen.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { modules.add("${it.group}:${it.module}") }
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult) queue.add(dependency.selected)
            }
        }
        val banned = modules.filter { module ->
            val group = module.substringBefore(':')
            bannedGroups.get().any { group == it || group.startsWith("$it.") }
        }
        if (banned.isNotEmpty()) {
            throw GradleException("Firebase/Play services on the release classpath (design 19 removed FCM): $banned")
        }
        logger.lifecycle("${name}: none of ${bannedGroups.get()} in ${modules.size} release modules")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val release = variant.buildType == "release"
        if (release) {
            val guard = tasks.register<CheckNoGoogleServices>("check${cap}HasNoGoogleServices") {
                rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                bannedGroups.set(listOf("com.google.firebase", "com.google.android.gms", "com.google.android.datatransport"))
            }
            tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(guard) }
        }
        val check = tasks.register<CheckDebugHook>(if (release) "check${cap}HasNoDebugHook" else "check${cap}HasDebugHook") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            names.set(listOf("LockTaskOverride", "lock_task_debug_override"))
            expectPresent.set(!release)
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(check) }
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
    // No Firebase or Play services (design 19 removed FCM: the SSE stream is the only nudge) -
    // checkReleaseHasNoGoogleServices fails assembleRelease if one comes back transitively.
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
