# 팀원 3 저장·동기화 수정 (코드 검토 요청 반영)

기준 브랜치: `feature/readiness-check`

## 1. 진행 중 기록 전송 차단 (재연결 시 DATA_CONFLICT 방지)
- 워치의 진행 중 시도는 `NOT_READY` 상태로 저장 (`beginLocalAttempt`). 종료(finalize) 시에만 `PENDING`
- `pendingUploads()`는 **종료된 시도만** 조회: SQL(`endStatus != 'IN_PROGRESS'`)과 `SyncPolicies.isUploadable` 이중 확인
- 워치 상태 보고·재전송 루프·설정 거부 판단의 대기 건수도 종료된 기록만 셈 (`countUploadable`)
- 스마트폰 수신부: `IN_PROGRESS` 기록은 `REJECTED_IN_PROGRESS`로 거부, attempts에 저장하지 않고 `receive_errors`에 보존
- 이전 버전이 이미 스마트폰에 저장한 진행 중 행은 최종 기록 도착 시 확정(`ReplaceInProgress`), 이전 행 내용은 `receive_errors`(IN_PROGRESS_SUPERSEDED)로 보존
- DB v1→v2 마이그레이션: 기존 워치 DB의 `PENDING + IN_PROGRESS` 행을 `NOT_READY`로 변경 (삭제 없음)

## 2. 해시 불일치 거부
- `saveReceivedAttempt()`: 전송 해시 ≠ 계산 해시이면 `REJECTED_HASH_MISMATCH`
  - attempts·conflicts에 저장하지 않음 (중복·충돌 판정보다 먼저 검사)
  - 받은 내용 전체·두 해시·출처를 `receive_errors` 테이블에 보존
- 워치는 거부 ACK를 받으면 `UPLOAD_REJECTED` 상태로 바꾸고 자동 재전송 중지 → `WearSyncManager.requeueRejectedUploads()`로 관리자 수동 재전송
- 스마트폰 동기화 게이트·무결성 검사(9번)에 미해결 수신 오류·거부 건수 포함, `receive_errors.csv`를 ZIP에 추가

## 3. 시행 중 이벤트 즉시 영구 저장
- `AttemptRecorder`가 START → 각 이벤트 → 종료를 **단일 순서 큐**로 저장 (관찰 순서 = 저장 순서)
- 이벤트마다 `ExperimentRepository.appendLocalEvent()`로 커밋 (IN_PROGRESS 시도에만, 같은 seq 중복 방지)
- 시각은 호출 즉시 단조 시계로 기록, 저장은 백그라운드 → 측정 시간에 저장 지연 미포함
- 강제 종료 후 재실행 시 `invalidateInterruptedAttempts()`가 **DB에 남은 이벤트로** INVALID 기록 생성 (종료 이벤트는 만들지 않음)

## 판단 로직 분리
- 새 파일 `shared/.../sync/SyncPolicies.kt`: 전송 대상, 수신 판단, ACK 후 상태, 복구 기록 → 순수 함수로 JVM 테스트
- 새 파일 `shared/.../util/HciLog.kt`: JVM 단위 테스트에서도 동작하는 Logcat 래퍼

## 추가 테스트 (JVM, `./gradlew :shared:testDebugUnitTest`)
| 파일 | 검증 |
|---|---|
| `SyncPoliciesTest` A1~A5 | 재연결 중 진행 기록: 전송 대상 제외, 폰 거부, 이후 최종 기록 정상 저장(충돌 아님), 구버전 진행 행 확정 |
| `SyncPoliciesTest` B1~B6 | 해시 불일치: 거부, 중복·충돌보다 우선, 실제 직렬화 경로, 워치 재전송 중지, 해시 일치 시만 SYNCED |
| `SyncPoliciesTest` C1~C2 | 강제 종료 복구 기록: 저장 이벤트 유지, 종료 이벤트 생성 안 함, 해시 안정 |
| `AttemptRecorderPersistenceTest` C3~C6 | 종료 전 이벤트가 순서대로 저장됨, 정상 종료 순서, 느린 저장소가 시각·호출에 영향 없음, 종료 후 입력 미저장 |

실기기 확인(모임): 워치 시행 중 블루투스 끊기→재연결, 시행 중 앱 강제 종료→재실행 후 sync_log/receive_errors/events 확인
