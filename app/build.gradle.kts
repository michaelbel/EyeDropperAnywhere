plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private val gitCommitsCount: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "org.michaelbel.eyedropperanywhere"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "org.michaelbel.eyedropperanywhere"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = gitCommitsCount
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "myapplication"
            keyPassword = "password"
            storeFile = rootProject.file(".github/debug-key.jks")
            storePassword = "password"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.service)
}
