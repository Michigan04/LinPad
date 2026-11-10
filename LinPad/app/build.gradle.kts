plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The package name must stay exactly 10 characters: tools/bundle.py rewrites
    // "com.termux" to it inside the prebuilt QEMU binaries.
    namespace = "dev.linpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.linpad"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        // Sign with every scheme: some manufacturers' installers reject v2-only packages.
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        // Sideloadable build: not debuggable, signed with the development key until
        // there is a real release key.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "abi"
    productFlavors {
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    packaging {
        jniLibs {
            // QEMU is exec()'d from the native library directory, so it must be extracted to disk.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
