plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.aidj.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aidj.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // Default backend URL can be set at build time: ./gradlew assembleRelease -PaidjBackendUrl=https://...
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"${project.findProperty("aidjBackendUrl") ?: ""}\"")
    }

    signingConfigs {
        // Release builds are signed with a key supplied via gradle properties / env (never committed).
        // Without one, the release APK is signed with the debug key so it is still installable for testing.
        create("release") {
            val ks = (project.findProperty("aidjKeystore") as String?) ?: System.getenv("AIDJ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = (project.findProperty("aidjKeystorePassword") as String?) ?: System.getenv("AIDJ_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("aidjKeyAlias") as String?) ?: System.getenv("AIDJ_KEY_ALIAS")
                keyPassword = (project.findProperty("aidjKeyPassword") as String?) ?: System.getenv("AIDJ_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":dj-audio-engine"))
    implementation(project(":dj-core"))

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
