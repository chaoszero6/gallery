import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.gallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.gallery"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type (arm64 for phones, x86_64 for emulators): the ONNX/MapLibre native
    // libraries are large, so a universal APK would carry both for nothing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Live API test reads these; it is skipped when they are absent.
            listOf("nc.url", "nc.user", "nc.appPassword").forEach { k ->
                localProps.getProperty(k)?.let { test.systemProperty(k, it) }
            }
        }
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        jniLibs {
            // The NPU (Qualcomm Hexagon) loads its half of QNN from a real directory, so native
            // libraries are extracted at install instead of being read from inside the APK.
            useLegacyPackaging = true
            // Only the HTP NPU backend is used. Keep Snapdragon 8 Gen 2 (V73) and newer:
            // 8 Gen 3 (V75), 8 Elite (V79, Galaxy S25), 8 Elite Gen 5 (V81).
            excludes += listOf("**/libQnnGpu.so", "**/libQnnDsp*.so", "**/libQnnHtpV68*.so", "**/libQnnHtpV69*.so")
        }
    }
}

// The Qualcomm build of ONNX Runtime only ships arm64 libraries. The emulator's x86_64 libraries
// come from the regular build of the same version, which has the same Java API.
val ortX86: Configuration by configurations.creating { isTransitive = false }

abstract class ExtractOrtX86 : DefaultTask() {
    @get:InputFiles abstract val aars: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction fun extract() {
        fs.sync {
            aars.forEach { from(archives.zipTree(it)) { include("jni/x86_64/*.so") } }
            eachFile { path = "x86_64/$name" }
            includeEmptyDirs = false
            into(outputDir)
        }
    }
}

val extractOrtX86 = tasks.register<ExtractOrtX86>("extractOrtX86") { aars.from(ortX86) }
androidComponents {
    onVariants { v -> v.sources.jniLibs?.addGeneratedSourceDirectory(extractOrtX86, ExtractOrtX86::outputDir) }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.browser)
    implementation(libs.exifinterface)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coil.video)
    implementation(libs.telephoto)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    implementation(libs.maplibre)
    implementation(libs.onnxruntime.qnn)
    ortX86(libs.onnxruntime.cpu) { artifact { type = "aar" } }
    implementation(libs.mlkit.labeling)
    implementation(libs.mlkit.text)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.serialization.json)
}
