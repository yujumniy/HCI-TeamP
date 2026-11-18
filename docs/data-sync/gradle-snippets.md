# 기존 프로젝트에 병합할 Gradle 설정

## settings.gradle.kts
```kotlin
include(":mobile", ":wear", ":shared")
```

## 루트 build.gradle.kts plugins 블록
```kotlin
id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false   // Kotlin 버전과 맞출 것
```
※ 저장소의 Kotlin 버전이 다르면 serialization 플러그인과 KSP 버전을 그 버전에 맞춥니다.
   (Kotlin 2.x 이면 serialization 플러그인도 2.x, KSP 는 2.x-1.0.x)

## mobile/build.gradle.kts, wear/build.gradle.kts dependencies
```kotlin
implementation(project(":shared"))
```
phone 에서 serialization 디코딩을 직접 쓰므로 phone·wear 에도 플러그인 추가:
```kotlin
id("org.jetbrains.kotlin.plugin.serialization")
```

## Data Layer 통신 필수 조건 (안 맞으면 메시지가 아예 안 옴)
- phone 과 wear 의 **applicationId 가 동일**해야 함 (예: com.hci.teamp)
- 두 APK 가 **같은 서명 키**로 서명되어야 함 (팀원마다 debug.keystore 가 다르면 통신 실패
  → 저장소에 공용 debug keystore 를 두고 signingConfigs 로 지정 권장)
- wear 매니페스트에 `<uses-feature android:name="android.hardware.type.watch" />`
