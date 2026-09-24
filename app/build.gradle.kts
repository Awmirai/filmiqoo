plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun quotedBuildConfig(value:String):String =
    "\"" + value.replace("\\","\\\\").replace("\"","\\\"") + "\""

val debugApiBaseUrl =
    System.getenv("FILMIQOO_DEBUG_API_BASE_URL") ?: "http://10.0.2.2:8080"
val releaseApiBaseUrl =
    System.getenv("FILMIQOO_RELEASE_API_BASE_URL") ?: ""
val releaseKeystorePath =
    System.getenv("FILMIQOO_KEYSTORE_PATH").orEmpty()
val releaseStorePassword =
    System.getenv("FILMIQOO_KEYSTORE_PASSWORD").orEmpty()
val releaseKeyAlias =
    System.getenv("FILMIQOO_KEY_ALIAS").orEmpty()
val releaseKeyPassword =
    System.getenv("FILMIQOO_KEY_PASSWORD").orEmpty()
val requireReleaseSigning =
    System.getenv("FILMIQOO_REQUIRE_SIGNING")?.equals("true",ignoreCase=true)==true

android {
    namespace = "com.filmiqoo.app"
    compileSdk = 35

    defaultConfig {
        applicationId =
            System.getenv("FILMIQOO_APPLICATION_ID") ?: "com.filmiqoo.previewfix"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("FILMIQOO_VERSION_CODE")?.toIntOrNull() ?: 4
        versionName = System.getenv("FILMIQOO_VERSION_NAME") ?: "0.4-connected-preview"
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\""+(System.getenv("FILMIQOO_FIREBASE_API_KEY") ?: "").replace("\\","\\\\").replace("\"","\\\"")+"\""
        )
        buildConfigField(
            "String",
            "FIREBASE_APP_ID",
            "\""+(System.getenv("FILMIQOO_FIREBASE_APP_ID") ?: "").replace("\\","\\\\").replace("\"","\\\"")+"\""
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            "\""+(System.getenv("FILMIQOO_FIREBASE_PROJECT_ID") ?: "").replace("\\","\\\\").replace("\"","\\\"")+"\""
        )
        buildConfigField(
            "String",
            "FIREBASE_SENDER_ID",
            "\""+(System.getenv("FILMIQOO_FIREBASE_SENDER_ID") ?: "").replace("\\","\\\\").replace("\"","\\\"")+"\""
        )
    }

    signingConfigs {
        create("release") {
            if(releaseKeystorePath.isNotBlank()) {
                storeFile=file(releaseKeystorePath)
                storePassword=releaseStorePassword
                keyAlias=releaseKeyAlias
                keyPassword=releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "FILMIQOO_API_BASE_URL",
                quotedBuildConfig(debugApiBaseUrl)
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "FILMIQOO_API_BASE_URL",
                quotedBuildConfig(releaseApiBaseUrl)
            )
            if(releaseKeystorePath.isNotBlank()) {
                signingConfig=signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
        htmlReport = true
    }

}

val verifyProductionReleaseConfig=tasks.register("verifyProductionReleaseConfig") {
    doLast {
        require(releaseApiBaseUrl.startsWith("https://")) {
            "FILMIQOO_RELEASE_API_BASE_URL must be a non-empty HTTPS URL"
        }
        if(requireReleaseSigning) {
            require(releaseKeystorePath.isNotBlank()) {
                "FILMIQOO_KEYSTORE_PATH is required for signed production releases"
            }
            require(releaseStorePassword.isNotBlank()) {
                "FILMIQOO_KEYSTORE_PASSWORD is required for signed production releases"
            }
            require(releaseKeyAlias.isNotBlank()) {
                "FILMIQOO_KEY_ALIAS is required for signed production releases"
            }
            require(releaseKeyPassword.isNotBlank()) {
                "FILMIQOO_KEY_PASSWORD is required for signed production releases"
            }
        }
    }
}

tasks.matching { it.name=="preReleaseBuild" }.configureEach {
    dependsOn(verifyProductionReleaseConfig)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
