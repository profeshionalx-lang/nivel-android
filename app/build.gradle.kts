plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nivel.trainer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nivel.trainer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Базовый URL REST API бэкенда Nivel (репо NIVEL), прод по умолчанию.
        // Для локальной разработки против `next dev` переопределить на
        // "http://10.0.2.2:3000/" (эмулятор) — потребует cleartext-разрешения в debug.
        buildConfigField("String", "API_BASE_URL", "\"https://nivel-five.vercel.app/\"")

        // Базовый URL Nivel (тот же хост, что API) — для построения redirect_uri
        // callback'а Гречки, который перехватывает WebView (см. GrechkaWebView).
        buildConfigField("String", "NIVEL_URL", "\"https://nivel-five.vercel.app\"")
        // База падел-платформы Гречка: грузим `${GRECHKA_URL}/auth-nivel.html` в WebView.
        buildConfigField("String", "GRECHKA_URL", "\"https://www.grecha.one\"")

        // Нативный Google Sign-In fallback требует google-services.json + SHA-1 из
        // Firebase-проекта grechka-6bdb7, которого в репозитории нет. Пока выключен:
        // включить, добавив google-services.json + firebase-auth/play-services-auth (см. #5).
        buildConfigField("boolean", "GOOGLE_SIGNIN_ENABLED", "false")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room экспортирует схему в VCS — нужно для будущих миграций и контроля изменений.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
