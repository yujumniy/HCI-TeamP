# HCI-TeamP.

HCI 팀 프로젝트 저장소입니다.

## 프로젝트 개요
- 주제: (작성 예정)
- 목표: (작성 예정)

## 팀원
| 이름 | 역할 |
| --- | --- |
|  |  |

## 폴더 구조
```
.
├── docs/      # 리서치, 인터뷰, 페르소나, 회의록 등 문서
├── design/    # 와이어프레임, 프로토타입, 디자인 시스템
├── mobile/    # 갤럭시 폰 앱 (Android, Kotlin + Jetpack Compose)
└── wear/      # 갤럭시 워치 앱 (Wear OS, Kotlin + Compose for Wear OS)
```

## 개발 환경
- Android Studio (최신 안정 버전), JDK 17 이상
- 폰 앱: minSdk 26 / 워치 앱: minSdk 30 (Wear OS 3+, 갤럭시 워치4 이후)
- 폰과 워치 앱은 같은 `applicationId`(`com.hci.teamp`)를 써야 Data Layer로 통신됩니다.

## 에뮬레이터로 실행하기
1. Android Studio에서 이 폴더를 엽니다 (Gradle 동기화 대기).
2. **Device Manager**에서 기기 2개를 만듭니다.
   - 폰: Phone 카테고리 (예: Pixel 8, API 34, **Google Play** 이미지)
   - 워치: Wear OS 카테고리 (예: Wear OS Large Round, API 34)
3. 두 에뮬레이터를 켠 뒤 **Device Manager → 폰 옆 ⋮ → Pair Wearable**로 워치와 페어링합니다.
   (폰에 Wear OS 앱 설치 안내가 나오면 따라 진행)
4. 상단 실행 설정에서 `mobile`을 폰 에뮬레이터에, `wear`를 워치 에뮬레이터에 각각 실행합니다.
5. 폰의 **워치로 보내기** / 워치의 **폰으로 보내기** 버튼으로 메시지가 오가는지 확인합니다.

> 실제 갤럭시 기기에서도 같은 방법으로 실행할 수 있습니다 (개발자 옵션 → USB/무선 디버깅).

## 협업 규칙
- `main` 브랜치에 직접 push하지 않고, 작업 브랜치를 만든 뒤 Pull Request로 합칩니다.
- 브랜치 이름: `feat/기능명`, `fix/버그명`, `docs/문서명`
