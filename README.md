# HCI-TeamP.

HCI 5팀 프로젝트 저장소입니다.

## 프로젝트 개요
- 주제: 스마트폰과 스마트위치에서 음향 조절 방식 비교
- 목표: 물리버튼을 이용하는 것과 터치 슬라이더를 이용하는 방법 중 어느 것이 음향을 더 정확하게 조절할 수 있는가? 

## 팀원
| 이름 | 학번 | 개인 브랜치 |
| --- | --- | --- |
| 박유정 | 23101918 | `member/park-yujeong` |
| 이운채 | 23101941 | `member/lee-unchae` |
| 김희원 | 24102060 | `member/kim-heewon` |
| 이나윤 | 24101950 | `member/lee-nayoon` |

## 주요 기능
- **볼륨 오버레이**: 볼륨 키, 갤럭시 볼륨 패널, 워치 베젤 등 어떤 방법으로 볼륨을 바꿔도 다른 앱 위에 `60%`, `9 / 15단계`가 2초 동안 표시됩니다.
- **폰 ↔ 워치 연동**: 한쪽에서 볼륨을 바꾸면 상대 기기에도 `폰 · 미디어 60%`처럼 표시됩니다. 두 기기 모두 '볼륨 표시'를 켜 두어야 합니다.
- **앱 화면**: 미디어, 벨소리, 알림, 알람, 통화 볼륨을 %와 단계로 보여 주고 −/+ 버튼으로 조절할 수 있습니다.

## 폴더 구조
```
.
├── docs/      # 리서치, 인터뷰, 페르소나, 회의록 등 문서
├── design/    # 와이어프레임, 프로토타입, 디자인 시스템
├── mobile/    # 갤럭시 폰 앱 (Android, Kotlin + Jetpack Compose)
├── wear/      # 갤럭시 워치 앱 (Wear OS, Kotlin + Compose for Wear OS)
└── shared/    # 폰·워치 공용 코드: 볼륨 감지(VolumeMonitor), 오버레이(VolumeOverlay), 서비스
```

## 개발 환경
- Android Studio 2026.2 이상 (Gradle 9.5, Android Gradle Plugin 9.3, compileSdk 36). Gradle JDK는 Android Studio 기본값(JetBrains Runtime) 그대로 두면 됩니다.
- 폰 앱: minSdk 26 / 워치 앱: minSdk 30 (Wear OS 3+, 갤럭시 워치4 이후)
- 폰과 워치 앱은 같은 `applicationId`(`com.hci.teamp`)를 써야 Data Layer로 통신됩니다.

## 에뮬레이터로 실행하기
1. Android Studio에서 이 폴더를 엽니다 (Gradle 동기화 대기).
2. **Device Manager**에서 기기 2개를 만듭니다.
   - 폰: Phone 카테고리 (예: Pixel 8, API 34, **Google Play** 이미지)
   - 워치: Wear OS 카테고리 (예: Wear OS Large Round, API 34)
3. 두 에뮬레이터를 켠 뒤 **Device Manager → 폰 옆 ⋮ → Pair Wearable**로 워치와 페어링합니다.
   (폰에 Wear OS 앱 설치 안내가 나오면 따라 진행)
4. 상단 실행 설정에서 `phone`을 폰 에뮬레이터에, `wear`를 워치 에뮬레이터에 각각 실행합니다.
5. 각 앱에서 **볼륨 표시 켜기**를 누르고 '다른 앱 위에 표시' 권한을 허용합니다.
   - 워치에 권한 화면이 없으면 PC 터미널에서 실행합니다 (워치 에뮬레이터 선택: `adb devices`로 확인 후 `-s 기기ID`):
     ```bash
     adb -s <워치기기ID> shell appops set com.hci.teamp SYSTEM_ALERT_WINDOW allow
     ```
6. 에뮬레이터 오른쪽 툴바의 **볼륨 +/−** 버튼을 누르면 오버레이가 뜨고, 상대 기기에도 표시됩니다.

> 실제 갤럭시 기기에서도 같은 방법으로 실행할 수 있습니다 (개발자 옵션 → USB/무선 디버깅).

실험 모듈과 역할별 구현 파일은 [음량 조절 실험 공통 뼈대](docs/experiment-scaffold.md)를 참고하세요.

## 협업 규칙
- `main` 브랜치에 직접 push하지 않고, 작업 브랜치를 만든 뒤 Pull Request로 합칩니다.
- 브랜치 이름: `feat/기능명`, `fix/버그명`, `docs/문서명`

## 브랜치
| 브랜치 | 용도 |
| --- | --- |
| `main` | 발표·제출용 안정 버전 |
| `develop` | 팀 공동 작업 브랜치 (개인 작업을 여기로 PR) |
| `member/park-yujeong` | 박유정 개인 작업 |
| `member/lee-unchae` | 이운채 개인 작업 |
| `member/kim-heewon` | 김희원 개인 작업 |
| `member/lee-nayoon` | 이나윤 개인 작업 |

작업 흐름: 개인 브랜치에서 작업 → `develop`으로 Pull Request → 확인 후 `main`에 합치기
```bash
git checkout member/내이름
git pull origin develop   # 작업 전 최신 develop 내용 받아오기
```
