plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pierce.pocketai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pierce.pocketai"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// v1.6 source fix is stored as a reviewed patch. Apply only the MainActivity
// portion in the ephemeral build checkout before Android compilation.
val applyV16SourceFix by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(
        "bash", "-lc",
        "if grep -q 'private const val SYSTEM_PROMPT' app/src/main/java/com/pierce/pocketai/MainActivity.kt; then " +
            "awk '/^--- a\\/app\\/build.gradle.kts/{exit} {print}' ci/v1.6.patch > /tmp/v1.6-main.patch && " +
            "git apply --check /tmp/v1.6-main.patch && git apply /tmp/v1.6-main.patch; fi"
    )
}

tasks.named("preBuild") {
    dependsOn(applyV16SourceFix)
}

dependencies {
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
