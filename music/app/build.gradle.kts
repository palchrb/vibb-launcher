import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing, as in launcher/app/build.gradle.kts (the same key: the bridge's signature
// permission needs one signer, design 21 §2/§5). Each value comes from the env var first (CI),
// then from a Gradle property (local builds: ~/.gradle/gradle.properties, never this repo).
// Without a store file assembleRelease still works and produces an unsigned APK.
fun releaseSecret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull
        ?.takeIf { it.isNotBlank() }
val releaseStoreFile = releaseSecret("ANDROID_RELEASE_KEYSTORE_FILE", "handy.release.storeFile")

// Release CI passes the version from the tag (music-vX.Y.Z -> X*1_000_000 + Y*1_000 + Z, an RC
// minus 1; .github/workflows/music.yml). Local and debug builds use the literals below.
val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.let {
    it.toIntOrNull() ?: throw GradleException("-PversionCode must be an integer, got '$it'")
}
val versionNameOverride = providers.gradleProperty("versionName").orNull

android {
    namespace = "me.vibb.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.vibb.music"
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "0.0.1"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes.addAll(
            listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE-notice.md")
        )
    }

    lint {
        abortOnError = false
    }

    testOptions {
        // ReportTest builds Media3's PlaybackException, whose constructor reads SystemClock (a stub
        // in JVM tests). No other test touches Android.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // CI passes -PwarningsAsErrors=true (music.yml), like the launcher.
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").orNull == "true")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * The debug feed (design 21 §6 step 2: library, listings and files pushed with adb) lives in
 * src/debug only. This scans a variant's merged manifest and its APKs' dex for the loader's names:
 * a release build fails if any is there (a dependency of assembleRelease), and the debug build
 * checks they are there (a positive control). Like the launcher's CheckDebugHook.
 */
abstract class CheckDebugFeed : DefaultTask() {
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
                    // Dex strings are MUTF-8: ASCII names match as bytes.
                    val dex = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                    names.get().filterTo(found) { dex.contains(it) }
                }
            }
        }
        if (expectPresent.get()) {
            val missing = names.get() - found
            if (missing.isNotEmpty()) throw GradleException("Debug build lacks the debug feed ($missing) - the release check would prove nothing")
        } else if (found.isNotEmpty()) {
            throw GradleException("The debug-only feed is in a release build: $found (keep it in src/debug)")
        }
        logger.lifecycle("${name}: ${if (expectPresent.get()) "debug feed present" else "no debug feed"} in ${apks.map { it.name }}")
    }
}

/** No Firebase, Play services or Google data transport on the release classpath (design 21 §4.1,
 * like the launcher's checkReleaseHasNoGoogleServices). */
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
        if (banned.isNotEmpty()) throw GradleException("Firebase/Play services on the release classpath: $banned")
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
        val check = tasks.register<CheckDebugFeed>(if (release) "check${cap}HasNoDebugFeed" else "check${cap}HasDebugFeed") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            names.set(listOf("DebugFeedReceiver", "me.vibb.music.debugfeed", "debug-feed"))
            expectPresent.set(!release)
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(check) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    // OkHttp for audio: its Dns and network interceptor see the address actually connected to
    // (the public-address guard, logic/Addresses.kt).
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.squareup.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
