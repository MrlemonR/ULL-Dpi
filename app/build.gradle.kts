plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ull.dpi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ull.dpi"
        minSdk = 24
        targetSdk = 35
        // CI her derlemede numarayı artırıyor: yeni APK eskinin üstüne kurulur.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    signingConfigs {
        // Depo sırlarında anahtar varsa onunla imzala (her sürüm aynı imza →
        // güncelleme eskisinin üstüne kurulur); yoksa debug anahtarı.
        create("release") {
            val ks = System.getenv("ULL_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storeType = "pkcs12"
                storePassword = System.getenv("ULL_KEYSTORE_PASSWORD") ?: "ull-dpi"
                keyAlias = System.getenv("ULL_KEY_ALIAS") ?: "ull-dpi"
                keyPassword = System.getenv("ULL_KEY_PASSWORD") ?: "ull-dpi"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
