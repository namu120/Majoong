// 버전은 설치된 Android Studio 가 권하는 값으로 맞춰도 된다. (문서 요구: Gradle 8.0+, API 28+)
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
