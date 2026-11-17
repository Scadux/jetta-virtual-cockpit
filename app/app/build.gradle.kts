plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jetta.virtualcockpit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jetta.virtualcockpit"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sem dependências externas: o app usa apenas o framework Android e a
// biblioteca padrão do Kotlin. Isso mantém o APK leve e permite compilar
// também sem Gradle (ver build.sh na raiz do projeto).
dependencies {
}
