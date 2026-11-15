import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.majung.car"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.majung.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 서버 주소. 에뮬레이터에서 개발 PC의 localhost 는 10.0.2.2
        buildConfigField("String", "SERVER_URL", "\"${project.findProperty("majung.server") ?: "http://10.0.2.2:8787"}\"")
        // true 면 FusedLocation 대신 모의 경로를 재생한다(시연 자료에 모의임을 표시할 것)
        buildConfigField("boolean", "MOCK_LOCATION", "${project.findProperty("majung.mockLocation") ?: "false"}")
    }
    // 서명 정보는 저장소에 올리지 않는 keystore.properties 에서 읽는다. 없으면 release 는 서명되지 않는다.
    val keystoreProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")          // CarUxRestrictionsManager (AAOS 표준)
}

dependencies {
    // Pleos Connect SDK — 좌표·버전은 각 SDK 문서의 Importing 페이지 기준 (2026-11-15 확인)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")            // 초대 QR 생성

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
