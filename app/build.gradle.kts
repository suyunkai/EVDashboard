import java.util.zip.GZIPOutputStream
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins { id("com.android.application") }

// AAPT stores .wav assets without compression. Package lossless gzip copies,
// leaving the editable source assets and their original SHA-256 names intact.
// Use .packed: the Android asset merger transparently inflates files ending in .gz.
abstract class PackPresetAssets : DefaultTask() {
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @TaskAction fun pack() {
        val sourceRoot = sources.get().asFile
        val destination = destination.get().asFile
        destination.deleteRecursively()
        sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val compress = relative.matches(Regex("presets/[^/]+/audio/[a-f0-9]{64}\\.wav"))
            val target = destination.resolve(relative + if (compress) ".packed" else "")
            target.parentFile.mkdirs()
            if (compress) source.inputStream().use { input ->
                GZIPOutputStream(target.outputStream()).use { output -> input.copyTo(output) }
            } else source.copyTo(target, overwrite = true)
        }
    }
}
val packPresetAssets = tasks.register<PackPresetAssets>("packPresetAssets") {
    sources.set(layout.projectDirectory.dir("src/main/assets"))
    destination.set(layout.buildDirectory.dir("generated/packed-assets"))
}
android {
    namespace = "com.kooo.evdashboard"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kooo.evdashboard"
        minSdk = 30
        targetSdk = 34
        versionCode = 12
        versionName = "0.2.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("aospTestkey") {
            // Public AOSP test key, intentionally versioned for test builds.
            storeFile = rootProject.file("signing/aosp-testkey.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "aosp-testkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aospTestkey")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    testOptions { unitTests.isReturnDefaultValues = true }
    testBuildType = providers.gradleProperty("instrumentBuildType").getOrElse("debug")
    sourceSets.getByName("main").assets.directories.clear()
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(packPresetAssets, PackPresetAssets::destination)
}
dependencies {
    implementation("com.github.mwiede:jsch:0.2.20")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
tasks.register<Exec>("buildNativeTools") {
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/build-native.sh")
    inputs.files(fileTree(rootProject.file("native")))
    inputs.files(fileTree(rootProject.file("vendor")) { include("**/*.c", "**/*.h", "**/*.ac", "**/*.am", "**/*.in", "**/configure"); exclude("erofs-audit/config.h", "erofs-legacy/config.h") })
    inputs.file(rootProject.file("tools/build-native.sh"))
    outputs.dir("src/main/jniLibs/arm64-v8a")
}
tasks.named("preBuild") { dependsOn("buildNativeTools") }
