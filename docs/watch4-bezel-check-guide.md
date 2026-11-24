# Galaxy Watch4 터치 베젤(C3) 검증 가이드

모임 전 준비 · 회전 이벤트 확인 · 이벤트가 안 들어올 때 원인 확인 · 감도 설정 위치

- 브랜치: `feature/wear-input` · 관련 문서: `docs/phase1-wear-input.md`
- 원칙(명세 4.4): 베젤 이벤트가 안 들어와도 **물리 버튼·홈/뒤로 버튼·물리 회전 베젤로 대체하지 않습니다.** 원인을 확인하고 결과를 팀에 보고합니다.

---

## 1. 모임 전 준비 (각자 PC)

| 순서 | 할 일 | 확인 |
| --- | --- | --- |
| 1 | Android Studio **Rabbit 2 (2026.2.2) 이상** 설치 | 프로젝트가 AGP 9.3.3 / Gradle 9.5.0을 씀 |
| 2 | GitHub Desktop에서 `HCI-TeamP` Clone → 브랜치 **`feature/wear-input`** 선택 | 위쪽 Current branch 표시 |
| 3 | Android Studio **File → Open → `HCI-TeamP` 맨 위 폴더** (`settings.gradle.kts`가 있는 곳) | 왼쪽에 phone / shared / wear |
| 4 | Sync 완료까지 대기 (처음엔 다운로드로 수 분) | Build 창에 failed 없음 |
| 5 | 실행 설정 **wear** → **≡ → Build → Assemble Module 'HCI-TeamP.wear'** | `BUILD SUCCESSFUL` |
| 6 | 실행 설정 **wear → Edit Configurations → Launch: Specified Activity**<br>`com.hci.teamp.experiment.input.check.WatchInputCheckActivity` | 저장 |

- "Project update recommended (AGP Upgrade)" 알림은 **누르지 않습니다** (팀 공용 설정이 바뀜).
- Windows Hypervisor 경고가 뜨면 Enable 후 재부팅 (에뮬레이터용).
- 미리 에뮬레이터(Wear OS Large Round)로 앱이 뜨는지 확인해 두면 모임 시간을 아낄 수 있습니다.
  에뮬레이터 회전 입력: 에뮬레이터 옆 **⋯ → Extended controls → Rotary input**
  (단, 에뮬레이터는 Watch4 터치 베젤이 아니므로 **앱의 처리 로직**만 확인됩니다.)

## 2. 모임 전 준비 (Watch4)

1. 충전 50% 이상, **PC와 같은 와이파이**에 연결 (설정 → 연결 → Wi-Fi)
2. **개발자 모드:** 설정 → 시계 정보 → 소프트웨어 → **소프트웨어 버전 5번 탭**
3. **설정 → 개발자 옵션:** **ADB 디버깅 ON**, **무선 디버깅 ON**
4. **터치 베젤 ON:** 설정에서 "터치 베젤" 검색 (One UI Watch 버전에 따라 디스플레이 또는 고급 기능 메뉴 안에 있음)
5. 기기 자체에서 베젤이 동작하는지 먼저 확인: 워치 앱 목록이나 설정 목록에서 **베젤을 문질러 스크롤되는지** 확인
6. 기록할 기기 정보: 모델명(설정 → 시계 정보), One UI Watch / Wear OS 버전, 화면 크기(40/44mm)

## 3. 워치 연결 · 설치

1. Android Studio 위쪽 기기 선택 → **Pair Devices Using Wi-Fi** → **Pair using pairing code**
2. 워치: 개발자 옵션 → 무선 디버깅 → **새 기기 페어링** → 화면의 코드를 PC에 입력
3. 위쪽 기기 선택에 Watch4가 보이면 실행 설정 **wear** → **▶**
4. 워치에 **C3·C4 입력 검증** 화면이 뜨면 성공 (앱 목록에도 같은 이름의 아이콘이 생김)

명령어로 연결할 때 (adb 위치: `C:\Users\<사용자>\AppData\Local\Android\Sdk\platform-tools\adb.exe`):
```
adb pair <워치IP>:<페어링포트>      # 페어링 코드 입력
adb connect <워치IP>:<무선디버깅포트>
adb devices                          # 워치가 device로 보이면 연결됨
adb shell am start -n com.hci.teamp/com.hci.teamp.experiment.input.check.WatchInputCheckActivity
```

## 4. 회전 이벤트 확인 순서

**Logcat 준비:** Android Studio 아래 **Logcat** 탭 → 기기 Watch4 선택 → 검색창에 `tag:HCI_WEAR_INPUT`

| 단계 | 할 일 | 정상일 때 Logcat |
| --- | --- | --- |
| ① 신호 수신 | 검증 화면 첫 화면(준비 버튼 화면)에서 베젤을 한 칸 문지름 | `MOTION_RAW ... rotary_source=true;source=0x...;device=...;axis_scroll=...` 그리고 `IGNORED ... not_active;px=...` |
| ② 방향 | 메뉴 → 조건 **C3** → 준비 → 카운트다운 후 **시계 방향** 한 칸 | `ROTARY ... dir=1;steps=1` 다음 `VALUE_CHANGE ... 50->55` 처럼 +5 |
| ③ 반시계 | 반시계 방향 한 칸 | `dir=-1;steps=-1`, 값 −5 |
| ④ 감도 | 한 칸 돌릴 때 `ROTARY`가 **몇 줄** 찍히는지, `px=` 값이 얼마인지 기록 | 한 칸 = 1줄이면 PER_EVENT 그대로 사용 |
| ⑤ 재접촉 | 돌리다 손을 떼고 1초 뒤 다시 돌림 | `GESTURE_START derived;gap_ms=...` 후 값이 한 칸씩만 변함 (튀지 않음) |
| ⑥ 단계 진동 | 한 칸씩 돌리며 손목으로 느낌 확인 (메뉴에서 약하게/강하게 바꿔 비교) | 한 칸마다 `HAPTIC ... played=true` 한 줄, 진동도 한 번 |
| ⑦ 화면 간섭 | 베젤을 문지르는 동안 | `SCREEN_TOUCH_DOWN`이 **안** 찍히는 것이 정상 (찍히면 기록) |

- 항목마다 20회씩 하고, 결과 화면에서 **성공/실패**를 누르면 메뉴의 "집계"에 쌓입니다.
- 끝나면 메뉴 → **CSV 내보내기** → PC에서
  ```
  adb pull /sdcard/Android/data/com.hci.teamp/files/HCI_Experiment/
  ```

## 5. 베젤 이벤트가 안 들어올 때: 원인 확인 순서

위에서부터 하나씩 확인하고, 해당하는 결과를 그대로 기록해 주세요.

| 증상 (Logcat `tag:HCI_WEAR_INPUT`) | 가능한 원인 | 확인 방법 |
| --- | --- | --- |
| **`MOTION_RAW`가 전혀 안 찍힘** | ① 터치 베젤 설정 꺼짐 | 워치 설정에서 터치 베젤 ON, 설정 목록에서 베젤 스크롤이 되는지 |
| | ② 검증 앱이 화면 앞에 없음 / 화면이 꺼짐 | 앱 화면이 켜진 상태에서 다시 시도 |
| | ③ 기기가 회전 이벤트를 앱에 보내지 않음 | 아래 **시스템 로그 확인** 실행 후 결과를 팀에 공유 |
| **`MOTION_RAW`는 찍히는데 `rotary_source=false`** | 회전이 다른 입력 종류로 들어옴 | 그 줄의 `source=0x...`, `device=...` 값을 그대로 기록 |
| **`MOTION_RAW`는 있는데 `ROTARY`/`IGNORED`가 없음** | 화면 포커스 문제 (메뉴 화면에 있음) | 메뉴에서 **돌아가기** → 준비 화면에서 다시 시도. 그래도 없으면 기록 |
| **`ROTARY`는 있는데 값이 안 바뀜 (`steps=0`)** | 감도 설정: 회전량 누적 모드에서 기준이 너무 큼 | 메뉴 → 변환 방식 **이벤트 1개 = 5**로, 또는 1단계 회전량을 작게 |
| **방향이 반대** | 기기의 원시값 부호가 반대 | 메뉴 → **방향** 반전 후 다시 확인, 어느 쪽이었는지 기록 |
| **한 칸에 값이 여러 번 바뀜** | 한 칸에 이벤트가 여러 개 옴 | 메뉴 → 변환 방식 **회전량 누적**, 1단계 회전량을 `px` 값에 맞게 조정 |
| **문지를 때 `SCREEN_TOUCH_DOWN`이 같이 찍힘** | 베젤 입력이 화면 터치로도 전달됨 | 좌표(`x=`, `y=`)를 기록 (화면 가장자리인지) |

**시스템 로그 확인 (앱과 무관하게 기기가 신호를 내는지)** — PC 명령 프롬프트에서:
```
adb shell dumpsys input > input_devices.txt
```
→ 파일에서 `rotary`, `bezel`, `wheel`이 들어간 입력 장치가 있는지 확인

```
adb shell getevent -lt
```
→ 실행해 둔 채 베젤을 문지름. 새 줄이 찍히는지, 어떤 장치(`/dev/input/eventN`)에서 나오는지 기록 (Ctrl+C로 종료)

```
adb logcat -v time > logcat_bezel.txt
```
→ 실행해 둔 채 베젤을 몇 번 문지르고 Ctrl+C → 파일에서 `bezel`, `rotary`, `wheel` 검색

이 세 파일과 앱 CSV를 함께 공유하면, 기기가 신호를 내지 않는 것인지 / 앱이 받지 못하는 것인지 구분할 수 있습니다.

## 6. 감도 설정 위치

**① 검증 앱 메뉴 (모임 중 바로 조정)** — 준비 화면 → **메뉴 → 베젤 설정**

| 메뉴 | 뜻 | 기본값 |
| --- | --- | --- |
| 변환 방식 | **이벤트 1개 = 5** / **회전량 누적** | 이벤트 1개 = 5 |
| 1단계 회전량 | 회전량 누적일 때 5가 바뀌는 데 필요한 px (16·24·32·48·64·96 순환) | 48 px |
| 재접촉 기준 | 이 시간 이상 회전이 없으면 새 동작으로 판단 (150·300·500·800 ms 순환) | 300 ms |
| 단계 진동 | 값이 한 단계 바뀔 때마다 진동: 끔 / 약하게(TICK) / 강하게(CLICK) | 강하게 |
| 방향 | 원시값 +를 시계 방향으로 볼지 | + = 시계 |

- 메뉴 값은 앱을 다시 켜면 기본값으로 돌아갑니다.
- 바꿀 때마다 Logcat에 `BEZEL_CONFIG`가 찍히고, 시행 CSV의 `bezel_config` 열에도 남습니다.

**② 코드 기본값 (파일럿에서 확정한 뒤 수정)**
`wear/src/main/java/com/hci/teamp/experiment/input/BezelStepper.kt` → `data class BezelConfig`
```kotlin
val mode: Mode = Mode.PER_EVENT,
val pixelsPerStep: Float = 48f,
val clockwiseIsPositive: Boolean = true,
val gestureGapMs: Long = 300L,
val haptic: Haptic = Haptic.CLICK,
```
확정값은 팀 합의 후 이 기본값만 바꾸고, `docs/phase1-wear-input.md`에 근거(측정한 px, 이벤트 수)를 기록합니다.

## 7. 모임 후 기록할 것
`docs/phase1-wear-input.md`의 검증표에 다음을 채웁니다.
- 기기 정보 (모델, One UI Watch/Wear OS 버전)
- 회전 이벤트 수신 여부, `source`/`device` 값
- 한 칸당 이벤트 수와 `px` 값 → 확정한 변환 방식·감도
- 방향, 재접촉, 화면 간섭 결과
- 항목별 성공/실패 횟수 (각 20회)
- 단계 진동: 한 번만 울리는지(시스템 진동과 겹치지 않는지), 약하게/강하게 중 선택 → 팀 합의 후 기본값 확정
