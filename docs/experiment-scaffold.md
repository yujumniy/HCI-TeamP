# 음량 조절 실험 공통 뼈대

Android Studio에서 저장소 루트를 연다. Gradle의 `:phone`은 기존 `mobile/` 디렉터리를 사용한다. 워치와 공통 모듈은 각각 `:wear`, `:shared`이다.

```powershell
.\gradlew.bat :phone:assembleDebug :wear:assembleDebug
```

기존 폰 앱 화면의 **실험 준비**에서 참가자 ID와 조건 순서를 확인할 수 있다. 이 화면에서 실제 시행을 시작할 수 있으려면 아래 명세·기기 검증 항목을 반영해야 한다.

| 담당 | 작업 파일 | 첫 검증 |
| --- | --- | --- |
| 1 | `mobile/…/experiment/input/PhoneVolumeKeys.kt`, `PhoneVolumeSlider.kt` | C1 볼륨 키와 C2 슬라이더가 실제 변경 시에만 가상 음량 이벤트를 발행하는지 검증. 폰에서 20회 반복 |
| 2 | `wear/…/experiment/input/WatchTouchBezel.kt`, `WatchVolumeSlider.kt` | Galaxy Watch4에서 C3 베젤 포커스와 C4 슬라이더 조작을 검증 |
| 3 | `shared/…/experiment/ExperimentData.kt` | 로컬 저장, 재연결 시 재전송, 중복 방지, CSV·ZIP 내보내기를 검증 |
| 4 | `mobile/…/experiment/ExperimentActivity.kt`, `ExperimentSchedule.kt`, `ExperimentAudio.kt`, `shared/…/experiment/TrialController.kt` | 참가자 등록, 과제 배정, 시행 UI와 상태 전환, 재생·입력·저장 연결 |

공통 모델은 `shared/…/experiment/`에 있다. C1/C2는 폰, C3/C4는 워치가 시행을 실행한다. 가상 음량은 0..100의 5단위 값이다. 시행과 입력은 같은 기기의 `SystemClock.elapsedRealtime()` 밀리초를 사용한다. 기기 간 전달에는 계산된 시행 시간과 출처 기기를 포함하고 서로 다른 기기의 시각 값을 빼지 않는다. 입력 이벤트에는 시도 ID, 전후 값과 기기 시각을 남긴다. 시행 상태는 준비 → 3초 카운트다운 → 조작 → COMPLETED/TIMEOUT/INVALID이며, 최종 상태와 이벤트 목록을 함께 저장한다.

## v1 원문 대조 후 확정할 항목

- 8개 과제의 ID와 시작·목표값, 참가자별 과제 순서, 연습·본 시행과 재시도 ID 규칙
- 제한 시간, 성공 판정, 무효 처리와 오버슈팅 계산, CSV 열과 이벤트 목록
- 벨소리 파일, 재생 시점과 반복 규칙, 기기 음량과 독립적인 재생 게인

작업 브랜치가 `develop`에 병합되면 최신 `develop`에서 개인 기능 브랜치를 만든다. 병합 전에 시작할 경우 `feature/experiment-scaffold`를 기준으로 각자 브랜치를 만든 뒤 해당 브랜치를 대상으로 PR을 열고, 뼈대 병합 후 대상을 `develop`으로 바꾼다.
