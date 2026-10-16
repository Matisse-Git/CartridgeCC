// LibretroDroid 0.14.0 (commit 8835c3098514390a271e36983957f7bb5f40abf1) built from its source; see UPSTREAM.md.
// Settings follow the tag's own libretrodroid/build.gradle, apart from the ABI and the optimisation noted below.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // The published library is a release build. The host draws and resamples every frame, so a debug
                // build of the app gets the same optimisation instead of an unoptimised copy.
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=RelWithDebInfo")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
}
