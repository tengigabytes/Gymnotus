import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tengigabytes.gymnotus"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.tengigabytes.gymnotus"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Devices with ODPM rails (Pixel 6 and later) are all arm64; the other ABIs would only carry unused
        // copies of the TLS libraries.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The release key lives outside the repository: keystore.properties (git-ignored) names the keystore and its
    // passwords. Without that file a release build is signed with the debug key, which is fine for trying the
    // minified build on a device and useless for publishing.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProperties.containsKey("storeFile")

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // Device maps are kept outside the app module so they can be contributed without touching code.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("data"))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The licence, the third-party list and the privacy policy are shown inside the app (Settings > About), from
// the same files that sit at the top of the repository.
abstract class LegalAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val documents: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("legal").apply { mkdirs() }
        documents.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val legalAssets = tasks.register<LegalAssetsTask>("legalAssets") {
    documents.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"), rootProject.file("PRIVACY.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssetsTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.libadb)
    implementation(libs.sun.security)
    implementation(libs.conscrypt)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
