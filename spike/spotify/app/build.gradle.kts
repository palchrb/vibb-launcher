import java.io.IOException
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Spotify's App Remote SDK is never committed (docs/testing/spotify-spike.md, "Terms"): the build
 * downloads the AAR from Spotify's own repository, pinned to a commit and checked against its
 * SHA-256, into app/libs/ (gitignored). Without network, drop the same file there by hand.
 */
val appRemoteAarName = "spotify-app-remote-release-0.8.0.aar"
val appRemoteAarUrl =
    "https://raw.githubusercontent.com/spotify/android-sdk/5aa4d62465f61a0677081ae9a3108177d0365fc3/app-remote-lib/$appRemoteAarName"
val appRemoteAarSha256 = "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"

abstract class FetchAppRemoteAar : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val aar: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = aar.get().asFile
        if (out.isFile && sha256Of(out) == sha256.get()) {
            logger.lifecycle("App Remote AAR present: ${out.name}")
            return
        }
        out.parentFile.mkdirs()
        val part = File(out.path + ".part")
        try {
            URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            part.delete()
            throw GradleException(
                "Could not download Spotify's App Remote AAR (${e.javaClass.simpleName}: ${e.message}).\n" +
                    "Download ${url.get()}\n" +
                    "(the file app-remote-lib/${out.name} of github.com/spotify/android-sdk, release 0.8.0)\n" +
                    "and drop it into ${out.parentFile} - its SHA-256 must be ${sha256.get()}.",
                e,
            )
        }
        val got = sha256Of(part)
        if (got != sha256.get()) {
            part.delete()
            throw GradleException("${out.name} from ${url.get()} has SHA-256 $got, expected ${sha256.get()}")
        }
        if (!part.renameTo(out)) throw GradleException("Could not move ${part.name} to $out")
        logger.lifecycle("App Remote AAR downloaded: $out")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchAppRemoteAar = tasks.register<FetchAppRemoteAar>("fetchAppRemoteAar") {
    url.set(appRemoteAarUrl)
    sha256.set(appRemoteAarSha256)
    aar.set(layout.projectDirectory.file("libs/$appRemoteAarName"))
}

/*
 * The signing key decides the SHA-1 that Spotify's dashboard pins, so it must stay the same from
 * build to build. With SPOTIFY_SPIKE_KEYSTORE_FILE (env, CI) or spike.keystore.file (a Gradle
 * property in ~/.gradle/gradle.properties, never this repo) the debug build is signed with that
 * PKCS12 keystore; otherwise with the machine's own debug key (~/.android/debug.keystore).
 */
fun spikeSecret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull?.takeIf { it.isNotBlank() }
val spikeStoreFile = spikeSecret("SPOTIFY_SPIKE_KEYSTORE_FILE", "spike.keystore.file")

android {
    namespace = "me.vibb.spotifyspike"
    compileSdk = 36

    defaultConfig {
        // Spotify's dashboard pins this package name; no ".debug" suffix.
        applicationId = "me.vibb.spotifyspike"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "spike-1"
    }

    signingConfigs {
        if (spikeStoreFile != null) {
            create("spike") {
                storeFile = file(spikeStoreFile)
                storeType = "pkcs12"
                storePassword = spikeSecret("SPOTIFY_SPIKE_KEYSTORE_PASSWORD", "spike.keystore.password")
                keyAlias = spikeSecret("SPOTIFY_SPIKE_KEY_ALIAS", "spike.keystore.alias") ?: "spike"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("spike")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // A throwaway spike: no translations, no Play listing.
        disable += setOf("MissingTranslation", "OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").orNull == "true")
    }
}

dependencies {
    // Spotify App Remote 0.8.0 (downloaded, never committed) and the JSON library it maps with.
    implementation(files(fetchAppRemoteAar.flatMap { it.aar }))
    implementation("com.google.code.gson:gson:2.11.0")
}
