plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Mirrors app/module.toml, which the IDE's own build system consumes.
android {
    namespace = "com.kirianov.kiasoulevplus2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kirianov.kiasoulevplus2"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Постійний ключ підпису для debug.
    //
    // Без нього кожна збірка в CI підписувалася новим ключем, який Gradle створює
    // на чистому раннері. Android відмовляє в оновленні застосунку, підписаного
    // іншим ключем (INSTALL_FAILED_UPDATE_INCOMPATIBLE), тому щоразу доводилося
    // спершу видаляти стару версію. З постійним ключем оновлення ставиться поверх.
    //
    // Ключ лише для debug: підписувати ним реліз не можна.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // module.toml declares languageLevel = "JAVA_11".
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // CarAppService розрізняє debug і release через BuildConfig.DEBUG.
        buildConfig = true
    }

    // The sources live under src/<variant>/kotlin, not the Gradle default src/<variant>/java.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/java", "src/test/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.car.app)
    // MediaBrowserServiceCompat: спосіб показати застосунок в Android Auto без Play Market.
    implementation(libs.androidx.media)

    // Used directly by GeneralData and the bluetooth services; module.toml relied on
    // these arriving transitively.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
