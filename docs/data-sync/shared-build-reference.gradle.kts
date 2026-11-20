// 3번(통신·데이터) 공통 모듈. 기존 저장소의 버전 카탈로그가 있으면 그 버전을 우선하세요.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hci.teamp.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 30 // Wear OS 3 (Galaxy Watch4) / Android 12+ (S22 Ultra)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val room = "2.6.1"
    api("androidx.room:room-runtime:$room")
    api("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    api("com.google.android.gms:play-services-wearable:18.2.0")

    testImplementation("junit:junit:4.13.2")
}
