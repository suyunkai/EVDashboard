plugins { id("com.android.application") }
android {
    namespace = "com.kooo.evdashboard"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kooo.evdashboard"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug { isDebuggable = true }
        release { isMinifyEnabled = false }
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    testOptions { unitTests.isReturnDefaultValues = true }
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
