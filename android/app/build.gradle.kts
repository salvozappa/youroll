plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.salvozappa.youroll"
    compileSdk = 37
    // Native code: the iOS stabilization motion estimator (../ThirdParty/YouRec/StabilizationMotion.cpp)
    // and RNNoise for Enhance audio (../ThirdParty/RNNoise), via JNI.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.salvozappa.youroll"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    // Enhance audio: ONNX Runtime ships about 40 MB of native code per ABI.
    // Target phones and the arm64 emulator are arm64, so the
    // x86_64 runtime is left out; DeepFilterNet3 reports itself unavailable there.
    packaging { jniLibs { excludes += listOf("lib/x86_64/libonnxruntime.so", "lib/x86_64/libonnxruntime4j_jni.so") } }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// The bundled title fonts (TitleDesign.kt) and their SIL OFL licences are the
// files in ../ThirdParty/TitleFonts, copied unmodified into assets/TitleFonts.
abstract class TitleFontAssets : DefaultTask() {
    @get:InputDirectory abstract val fonts: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction fun copy() {
        files.sync {
            from(fonts) { into("TitleFonts") }
            into(output)
        }
    }
}

val titleFontAssets = tasks.register<TitleFontAssets>("titleFontAssets") {
    fonts.set(rootProject.layout.projectDirectory.dir("../ThirdParty/TitleFonts"))
    output.set(layout.buildDirectory.dir("generated/titleFontAssets"))
}

// Enhance audio: DeepFilterNet3's official ONNX export (ThirdParty/DeepFilterNet3-ONNX,
// see its PROVENANCE.md) and the iOS DSP parameters
// (../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json), copied unmodified into assets/DeepFilterNet3.
abstract class DeepFilterAssets : DefaultTask() {
    @get:InputDirectory abstract val model: DirectoryProperty
    @get:InputFile abstract val dsp: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction fun copy() {
        files.sync {
            from(model) { include("*.onnx"); into("DeepFilterNet3") }
            from(dsp) { into("DeepFilterNet3") }
            into(output)
        }
    }
}

val deepFilterAssets = tasks.register<DeepFilterAssets>("deepFilterAssets") {
    model.set(rootProject.layout.projectDirectory.dir("ThirdParty/DeepFilterNet3-ONNX"))
    dsp.set(rootProject.layout.projectDirectory.file("../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json"))
    output.set(layout.buildDirectory.dir("generated/deepFilterAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(titleFontAssets, TitleFontAssets::output)
        variant.sources.assets?.addGeneratedSourceDirectory(deepFilterAssets, DeepFilterAssets::output)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
