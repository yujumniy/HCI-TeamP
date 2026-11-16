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

## 진행 상황
- **Phase 1 (최소 기술 검증)**: 스마트폰 C1 물리 버튼 · C2 슬라이더 검증 앱 → [docs/phase1-phone-input.md](docs/phase1-phone-input.md)
- 워치 C3·C4, 기기 간 통신, 오디오는 담당 팀원 브랜치에서 진행

## 폴더 구조
```
.
├── docs/      # 리서치, 인터뷰, 페르소나, 회의록 등 문서
├── design/    # 와이어프레임, 프로토타입, 디자인 시스템
├── phone/     # 스마트폰 앱 (C1·C2, 실험 관리) — Kotlin + Jetpack Compose
├── wear/      # 워치 앱 (C3·C4) — Kotlin + Compose for Wear OS
└── shared/    # 공통: 실험 설계·측정 규칙·이벤트 로그·시행 화면 UI
```

## 개발 환경
- Android Studio 2026.2 이상 (Gradle 9.5, Android Gradle Plugin 9.3, compileSdk 36). Gradle JDK는 Android Studio 기본값(JetBrains Runtime) 그대로 두면 됩니다.
- 폰 앱: minSdk 26 / 워치 앱: minSdk 30 (Wear OS 3+, 갤럭시 워치4 이후)
- 폰과 워치 앱은 같은 `applicationId`(`com.hci.teamp`)를 써야 Data Layer로 통신됩니다.

## 에뮬레이터로 실행하기
1. Android Studio에서 이 폴더를 열고 Gradle Sync
2. 실행 설정에서 `phone`(폰 에뮬레이터) 또는 `wear`(Wear OS 에뮬레이터) 선택 후 ▶
3. 폰·워치 연동이 필요하면 Device Manager → 폰 옆 ⋮ → **Pair Wearable**

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
