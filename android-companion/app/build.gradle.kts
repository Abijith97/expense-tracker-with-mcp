plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.abijith.expensetracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.abijith.expensetracker"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.13.0-expenditure-review"

        ndk {
            // Pixel 7a and 64-bit Windows-hosted Android emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Opt in for CI/coverage runs; ordinary demo APKs keep normal timings.
            enableAndroidTestCoverage = providers.gradleProperty("coverage")
                .map(String::toBoolean).getOrElse(false)
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testCoverage {
        jacocoVersion = "0.8.14"
    }
}

chaquopy {
    defaultConfig {
        // Match the installed Windows Python for build-time bytecode compilation.
        // The APK bundles its own interpreter: the phone needs no Python install.
        version = "3.11"
        pyc {
            src = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
