plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseStoreFilePath = providers.environmentVariable("SSH_VPN_RELEASE_STORE_FILE")
    .orElse(providers.gradleProperty("SSH_VPN_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.environmentVariable("SSH_VPN_RELEASE_STORE_PASSWORD")
    .orElse(providers.gradleProperty("SSH_VPN_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.environmentVariable("SSH_VPN_RELEASE_KEY_ALIAS")
    .orElse(providers.gradleProperty("SSH_VPN_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.environmentVariable("SSH_VPN_RELEASE_KEY_PASSWORD")
    .orElse(providers.gradleProperty("SSH_VPN_RELEASE_KEY_PASSWORD"))
    .orNull
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val bundleXrayCore = providers.gradleProperty("bundleXrayCore")
    .map(String::toBoolean)
    .orElse(false)
val appVersionName = "2.5.1"
val appVersionParts = appVersionName.split('.').map(String::toInt)
require(appVersionParts.size == 3 && appVersionParts.drop(1).all { it in 0..999 }) {
    "versionName must be SemVer with minor/patch in 0..999"
}
val appVersionCode = appVersionParts[0] * 1_000_000 +
    appVersionParts[1] * 1_000 +
    appVersionParts[2]

android {
    namespace = "com.stansful.sshvpnclient"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stansful.sshvpnclient"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        // Gradle 9.6 triggers a deprecation inside AGP 9.2.1; stay on its compatible wrapper.
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.work:work-runtime:2.11.2")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.crypto.tink:tink-android:1.22.0")
    implementation("com.github.mwiede:jsch:2.28.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val xrayAar = file("libs/libXray.aar")
    if (bundleXrayCore.get() && xrayAar.isFile) {
        implementation(files(xrayAar))
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
