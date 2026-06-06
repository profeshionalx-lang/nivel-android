import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// --- Release signing (опционально) -----------------------------------------
// Конфиг подписи берётся из (в порядке приоритета):
//   1) keystore.properties в корне модуля (локально, в .gitignore), либо
//   2) переменных окружения (CI/GitHub Secrets):
//        SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
// Если ничего не задано — release собирается БЕЗ подписи (unsigned APK).
// Это сознательно: настоящий keystore — секрет человека, его нет в репозитории.
// См. .github/workflows/android-ci.yml и README (раздел «Установка APK на телефон»).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFilePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

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

        // Базовый URL веб-Nivel (тот же хост, что API) — для построения внешних
        // ссылок (например, claim-инвайтов). Вход Гречки теперь возвращается на
        // custom-scheme deep link nivel://auth/callback, а не на этот хост.
        buildConfigField("String", "NIVEL_URL", "\"https://nivel-five.vercel.app\"")
        // База падел-платформы Гречка: открываем `${GRECHKA_URL}/auth-nivel.html`
        // в Chrome Custom Tabs (см. GrechkaCustomTab.launchGrechkaAuth).
        buildConfigField("String", "GRECHKA_URL", "\"https://www.grecha.one\"")

        // Нативный Google Sign-In fallback требует google-services.json + SHA-1 из
        // Firebase-проекта grechka-6bdb7, которого в репозитории нет. Пока выключен:
        // включить, добавив google-services.json + firebase-auth/play-services-auth (см. #5).
        buildConfigField("boolean", "GOOGLE_SIGNIN_ENABLED", "false")
    }

    signingConfigs {
        // Конфиг создаём только если заданы все четыре параметра подписи.
        // Иначе release остаётся без signingConfig → unsigned APK (всё ещё собирается).
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подписываем release-keystore'ом только если он предоставлен.
            // Без секретов CI/локалка соберут unsigned release APK (для отладки).
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
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

    implementation(libs.coil.compose)

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

    // C3 (#12) — фоновая заливка записи: WorkManager + Hilt-интеграция воркеров.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // G2 (#31) — push-уведомления через Firebase Cloud Messaging.
    // BOM закрепляет согласованные версии Firebase-артефактов.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
}
