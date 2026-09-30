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
val privacyPolicyUrl = System.getenv("FILMIQOO_PRIVACY_POLICY_URL").orEmpty()
val termsUrl = System.getenv("FILMIQOO_TERMS_URL").orEmpty()
val requireReleaseSigning =
    System.getenv("FILMIQOO_REQUIRE_SIGNING")?.equals("true",ignoreCase=true)==true

android {
    namespace = "com.filmiqoo.app"
    compileSdk = 36

    defaultConfig {
        buildConfigField("String","PRIVACY_POLICY_URL",quotedBuildConfig(privacyPolicyUrl))
        buildConfigField("String","TERMS_URL",quotedBuildConfig(termsUrl))
        applicationId =
            System.getenv("FILMIQOO_APPLICATION_ID") ?: "com.filmiqoo.previewfix"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("FILMIQOO_VERSION_CODE")?.toIntOrNull() ?: 5
        versionName = System.getenv("FILMIQOO_VERSION_NAME") ?: "1.0.0-rc1"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }

    lint {
        lintConfig=file("lint.xml")
        abortOnError=true
        checkReleaseBuilds=true
        textReport=true
        textOutput=file("build/reports/lint-results-debug.txt")
        htmlReport=true
    }

}

val verifyProductionReleaseConfig=tasks.register("verifyProductionReleaseConfig") {
    doLast {
        require(releaseApiBaseUrl.startsWith("https://")) {
            "FILMIQOO_RELEASE_API_BASE_URL must be a non-empty HTTPS URL"
        }
        if(requireReleaseSigning) {
            require(!System.getenv("FILMIQOO_APPLICATION_ID").isNullOrBlank()) {
                "FILMIQOO_APPLICATION_ID is required for production (use the existing Play application ID)"
            }
            require(privacyPolicyUrl.startsWith("https://") && termsUrl.startsWith("https://")) {
                "Published HTTPS privacy policy and terms URLs are required for production"
            }
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
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.29.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.29.0")
}
