import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :license-tool — the owner's private license generator (runs on the owner's PC, never in the app).
// It signs device-bound lifetime keys with the owner's ECDSA P-256 private key and keeps a registry.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.eggvyapar.licensetool.MainKt")
    applicationName = "license-tool"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
