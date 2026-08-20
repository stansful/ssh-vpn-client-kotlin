plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
    val moduleBuildPath = path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(moduleBuildPath))
}
