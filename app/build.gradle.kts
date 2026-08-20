plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
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
val appVersionName = "3.0.0"
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

    sourceSets {
        // MigrationTestHelper reads the exported Room schemas from the test APK assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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

        // Fail the build on lint errors instead of only reporting them.
        abortOnError = true

        // Existing debt is frozen in the baseline; new issues must be fixed.
        // Bootstrap: `./gradlew :app:lintDebug -Plint.baseline.bootstrap=true`, commit the file,
        // then delete and regenerate it after each cleanup pass.
        //
        // The reference is conditional on purpose: pointing lint at a missing baseline makes it
        // generate the file and then fail the build, which would turn every fresh clone red.
        val lintBaseline = file("lint-baseline.xml")
        if (lintBaseline.isFile || providers.gradleProperty("lint.baseline.bootstrap").isPresent) {
            baseline = lintBaseline
        }

        // TODO(IMPROVEMENT_PLAN 2.3): 54 hardcoded Compose strings remain. Once they are in
        // strings.xml, promote this to `error += "HardcodedText"` and regenerate the baseline.
        warning += "HardcodedText"

        // TODO(IMPROVEMENT_PLAN 1.1): flip to true once the baseline is committed and green.
        warningsAsErrors = false

        htmlReport = true
        xmlReport = true
        sarifReport = true
    }
}

// Exported Room schemas are the input for the migration tests. They must be committed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))

    // Bootstrap once with `./gradlew :app:detektBaseline` and commit the result, so the
    // existing backlog does not block CI while new violations still fail the build.
    baseline = rootProject.file("config/detekt/baseline.xml")
    source.setFrom(
        files(
            "src/main/java",
            "src/test/java",
            "src/androidTest/java",
        ),
    )
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
}

/** Fails when a broad `catch (e: Exception)` in coroutine code can swallow cancellation. */
val cancellationGuardScript = rootProject.file("scripts/check-cancellation.sh")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

if (cancellationGuardScript.isFile && !isWindowsHost) {
    val checkCancellationHandling by tasks.registering(Exec::class) {
        group = "verification"
        description = "Verifies coroutine code rethrows CancellationException before broad catches."
        workingDir = rootProject.projectDir
        commandLine("bash", cancellationGuardScript.absolutePath)
    }

    tasks.named("check").configure {
        dependsOn(checkCancellationHandling)
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
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
