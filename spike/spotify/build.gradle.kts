// Top-level build file of the Spotify engine spike: one module, app/. The launcher's and the music
// app's AGP and Kotlin.

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}
