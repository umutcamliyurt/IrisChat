plugins {
    alias(libs.plugins.android.application)
}

android {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "com.umut.irischat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.umut.irischat"
        minSdk = 24
        targetSdk = 35
        versionCode = 170
        versionName = "1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "META-INF/native-image/**"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/**"
            excludes += "groovy/**"
            excludes += "org/codehaus/groovy/**"
            excludes += "**/*.dylib"
            excludes += "**/*.dll"
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libsignal_jni_testing.so"
            excludes += "**/*.dylib"
            excludes += "**/*.dll"
        }
    }

    experimentalProperties["android.dexingUseFullClasspath"] = true
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.commons.codec)
    implementation(libs.glide)

    implementation("com.github.pircbotx:pircbotx:2.3.1")
    implementation("org.signal:libsignal-android:0.86.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
