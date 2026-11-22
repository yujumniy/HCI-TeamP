# Phase 1 · C3 터치 베젤 / C4 워치 슬라이더 (팀원 2)

브랜치 `feature/wear-input` (기준: `feature/experiment-scaffold`) · 명세서 3장 T2, 4.4, 4.5, 11.1, 17장, 19장

> 상태: **실기기 미검증.** 순수 로직 단위 테스트(11개)만 통과했습니다.
> Android 빌드와 Galaxy Watch4 동작은 아래 검증표를 채운 뒤 "검증 완료"로 바꿉니다 (명세 2장 원칙 4).

## 구현 파일
| 파일 | 내용 |
| --- | --- |
| `wear/…/experiment/input/WatchTouchBezel.kt` | C3. `ExperimentInput` 구현 + `Content {}` 화면 래퍼. 회전 이벤트 → ±5 |
| `wear/…/experiment/input/WatchHaptics.kt` | C3 단계 진동 (TICK 약 / CLICK 강) |
| `wear/…/experiment/input/BezelStepper.kt` | C3 변환 규칙 (방향, 감도, 재접촉). Android 의존성 없음 |
| `wear/…/experiment/input/WatchVolumeSlider.kt` | C4. `ExperimentInput` 구현 + `Content()`. 손잡이 드래그만 허용, 트랙 탭 차단 |
| `wear/…/experiment/input/WatchSliderMath.kt` | C4 좌표 ↔ 값 계산 (팀원 1의 SliderMath와 같은 규칙) |
| `wear/…/experiment/input/WatchRawInput.kt` | 원시 입력 기록 형식 (팀원 1의 RawInput과 같은 형식) |
| `wear/…/experiment/input/check/` | T2 검증 화면(`WatchInputCheckActivity`), CSV 기록 |
| `wear/src/main/res/values/styles.xml` | `Theme.HciWear.NoSwipe`: 쓸어서 닫기 끔 (C4 드래그 중 앱이 닫히는 것 방지) |
| `wear/src/test/…/WearInputLogicTest.kt` | 단위 테스트 11개 |

공통 모델(`shared/…/experiment/`)은 수정하지 않았습니다.
`libs.versions.toml`의 junit 추가는 팀원 1 브랜치와 같은 줄이라 합칠 때 충돌하지 않습니다.

## 팀원 4(시행 화면)가 연결하는 방법
```kotlin
// C3: 시행 화면 전체를 감싸야 베젤 회전을 받습니다 (포커스 필요).
//     안에 ScalingLazyColumn 같은 스크롤 목록을 두면 회전이 목록으로 빠지니 두지 마세요.
bezel.Content(Modifier.fillMaxSize()) { /* 목표·현재 값, 막대, 완료 버튼 */ }
bezel.start(attempt) { event -> controller.record(event) }   // ACTIVE가 되면
bezel.stop()                                                 // 끝나면

// C4: 화면 세로 가운데에 배치. Activity 테마는 Theme.HciWear.NoSwipe
slider.Content(Modifier.fillMaxWidth(0.8f))
slider.start(attempt) { event -> controller.record(event) }
slider.stop()
```
- 값이 실제로 바뀔 때만 `InputEvent`(이전값 → 현재값, `SystemClock.elapsedRealtime()`, `ExperimentDevice.WEAR`)를 냅니다.
- 원시 입력(회전 원본값, 손잡이 누름/뗌, 트랙 탭 차단, 경계값)은 `rawListener`로 따로 받습니다.
  공통 `EventType`에 INPUT_DOWN/INPUT_UP을 추가할지는 팀 합의가 필요합니다 (팀원 1과 같은 의견).
- **터치 베젤은 손가락을 대고 떼는 신호를 앱에 주지 않습니다.** 재접촉은 회전 이벤트 사이 간격으로만 추정하고
  `GESTURE_START`(derived)로 남깁니다. 이것을 INPUT_DOWN으로 기록하면 관찰하지 않은 입력을 만드는 셈이므로 그렇게 하지 않습니다 (명세 11.1).

## C3 변환 규칙 (파일럿에서 확정, 명세 19장)
`BezelConfig`로 바꿀 수 있고, 검증 화면 메뉴에서도 바로 바꿀 수 있습니다. 시행마다 설정값이 CSV에 남습니다.

| 설정 | 기본값 | 뜻 |
| --- | --- | --- |
| `mode` | PER_EVENT | 회전 이벤트 1개 = 5. ACCUMULATE면 회전량(px)을 모아 `pixelsPerStep`마다 5 |
| `pixelsPerStep` | 48 | ACCUMULATE 모드 감도 |
| `clockwiseIsPositive` | true | 원시값 +가 시계 방향인지. 시계 방향 = 증가가 되도록 맞춤 |
| `gestureGapMs` | 300 | 이 시간보다 오래 회전이 없으면 다음 회전을 새 동작으로 봄 |
| `haptic` | CLICK | 값이 한 단계 바뀔 때마다 진동 (OFF / TICK / CLICK). **사용 여부는 팀 합의 필요** |

- 새 동작이 시작되거나 방향이 바뀌면 남은 회전량을 버립니다 → 손을 뗐다 다시 댔을 때 값이 튀지 않음.
- 한 이벤트로 2단계 이상 바뀌면 중간값 없이 관찰된 값 하나만 냅니다.
- 단계 진동: 실제 갤럭시 터치 베젤처럼 한 칸마다 진동합니다. 값이 바뀔 때만 매번 같은 진동이고,
  0/100 경계에서 값이 안 바뀌면 진동하지 않으며, 목표 도달 시 별도 진동은 없습니다 (명세 5.1).
  진동할 때마다 `HAPTIC` 원시 기록(`effect`, `played`)이 남습니다.
  C1 물리 버튼의 딸깍 느낌과 맞춰 "주변부 입력 = 단계마다 촉각 피드백 있음, 슬라이더 = 없음"이 됩니다.
  파일럿에서 시스템 진동과 겹쳐 두 번 울리지 않는지 확인합니다.

## 검증 화면 실행
1. 실행 설정 `wear` → Edit Configurations → Launch: **Specified Activity**
   → `com.hci.teamp.experiment.input.check.WatchInputCheckActivity` → ▶
   (또는 워치 앱 목록의 **C3·C4 입력 검증** 아이콘)
2. 워치 **설정 → 고급 기능 → 터치 베젤**이 켜져 있는지 확인합니다.
3. **메뉴**에서 조건·검증 항목·과제 선택 → **준비** → 3초 카운트다운 → 조작 → **완료** → **성공/실패** 판정
4. 끝나면 메뉴 → **CSV 내보내기**, PC에서:
   ```
   adb pull /sdcard/Android/data/com.hci.teamp/files/HCI_Experiment/
   ```
   Logcat 태그 `HCI_WEAR_INPUT`으로 실시간 확인도 됩니다.

## 검증표 (각 20회, 명세 17장)
| 검증 항목 | 확인할 것 | 성공 | 실패 | 비고 |
| --- | --- | --- | --- | --- |
| **C3-이벤트 수신** (가장 먼저) | 베젤을 돌리면 `MOTION_RAW`가 찍히고 `rotary_source=true`인지. 안 찍히면 즉시 팀에 보고 | | | |
| C3-시계방향 | 한 칸에 +5, 100에서 멈춤 | | | |
| C3-반시계방향 | 한 칸에 −5, 0에서 멈춤 | | | |
| C3-재접촉 | 돌리다 떼고 다시 댈 때 값이 튀지 않음 | | | |
| C3-감도 | 한 칸에 이벤트 몇 개, px 값이 얼마인지 기록 → 변환 방식 결정 | | | |
| C3-단계 진동 | 한 칸에 진동 한 번, 경계·목표에서 추가 진동 없음, TICK/CLICK 중 어느 쪽이 느껴지는지 | | | |
| C3-화면 간섭 | 베젤을 문지를 때 `SCREEN_TOUCH_DOWN`이 같이 찍히는지 | | | |
| C4-드래그정확성 | 끄는 동안 5단위로 바로 갱신, 잡는 순간 값이 튀지 않음, 끌어도 앱이 안 닫힘 | | | |
| C4-트랙탭차단 | 손잡이 밖을 눌러도 값 그대로, 차단 횟수 증가 | | | |

## CSV (워치 `files/HCI_Experiment/`, 내보낼 때마다 새 파일 2개)
- `phase1_wear_input_trials_날짜.csv`: 시행 1회 = 1줄. 시도 ID, 검증 항목, 과제, 결과, 시간, 최종값, 오차, 값 변경 수, 회전 이벤트 수, 추정 동작 수, 트랙 탭 차단 수, 판정, 베젤 설정
- `phase1_wear_input_events_날짜.csv`: 원본 기록 전체 (`VALUE_CHANGE`, `ROTARY`, `GESTURE_START`, `MOTION_RAW`, `SCREEN_TOUCH_*`, `DOWN`/`UP`, `TRACK_TAP_BLOCKED`, `BOUNDARY`, `READY`/`START`/`COMPLETED`/`TIMEOUT`, `JUDGE`)

## 팀에 확인할 것
1. Watch4 터치 베젤이 회전 이벤트를 주지 않으면 C3을 어떻게 할지 (명세상 버튼 등으로 대체 불가 → 팀 결정 필요)
2. C3 단계 진동 사용 여부와 세기(TICK/CLICK) 확정 → 사용하면 제안서 조건 정의에 명시
3. 공통 `EventType`에 INPUT_DOWN/INPUT_UP 추가 여부 (팀원 1과 함께)
4. `SliderMath`를 폰·워치 공통 모듈로 옮길지
