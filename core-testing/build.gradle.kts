import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core-testing — shared fakes, fixed clocks and builders for tests in every module.
plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    api(libs.truth)
}
