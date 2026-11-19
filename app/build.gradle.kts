plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "dev.cartridge"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.cartridge"
        manifestPlaceholders["appLabel"] = "Cartridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                providers.gradleProperty("mgbaSource").orNull?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_MGBA=$it"
                }
                providers.gradleProperty("pcsxSource").orNull?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_PCSX=$it"
                }
                // -PsnesSource=..., -PnesSource=..., -PgenesisSource=..., -PsaturnSource=..., -PdreamcastSource=..., -Pps2Source=...,
                // -PgamecubeSource=...: an existing exact checkout of a pinned core.
                for ((property, content) in listOf("snesSource" to "SNES9X", "nesSource" to "FCEUMM", "genesisSource" to "GPGX", "saturnSource" to "SATURN",
                    "dreamcastSource" to "FLYCAST", "ps2Source" to "ARMSX2", "gamecubeSource" to "DOLPHIN")) {
                    providers.gradleProperty(property).orNull?.let { arguments += "-DFETCHCONTENT_SOURCE_DIR_$content=$it" }
                }
                // -Pps2Libraries=...: a prefix the PlayStation 2 engine's own dependency recipe has already built.
                providers.gradleProperty("ps2Libraries").orNull?.let { arguments += "-DCARTRIDGE_PS2_LIBRARIES=$it" }
                // Data files a core reads at run time are packed by the native build into this asset folder.
                arguments += "-DCARTRIDGE_ASSETS=${layout.buildDirectory.dir("generated/core-assets").get().asFile.invariantSeparatorsPath}"
                targets += listOf("mgba_libretro", "pcsx_rearmed_libretro", "snes9x_libretro", "fceumm_libretro",
                    "genesis_plus_gx_libretro", "mednafen_saturn_libretro", "flycast_libretro", "cartridge_saves")
            }
        }
    }
    // Dolphin's build files need CMake 3.25 or later.
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    // Releases are signed with this machine's debug key, so a release installs straight over a debug build and keeps its data.
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug") } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

room { schemaDirectory("$projectDir/schemas") }

val syncLicenseNotices by tasks.registering(Sync::class) {
    from(rootProject.file("licenses"))
    into(layout.buildDirectory.dir("generated/license-assets/licenses"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/license-assets"))
tasks.named("preBuild") { dependsOn(syncLicenseNotices) }
// The native build writes a core's data files; everything that reads the app's assets waits for it.
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/core-assets"))
for ((assets, native) in listOf("mergeDebugAssets" to "buildCMakeDebug", "mergeReleaseAssets" to "buildCMakeRelWithDebInfo")) {
    tasks.matching { it.name == assets }.configureEach { dependsOn(tasks.matching { it.name.startsWith(native) }) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.google.android.filament:filament-android:1.72.1")
    implementation("com.google.android.filament:gltfio-android:1.72.1")
    implementation("com.google.android.filament:filament-utils-android:1.72.1")
    implementation(project(":libretrodroid"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}
