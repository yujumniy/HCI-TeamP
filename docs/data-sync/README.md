# 팀원 3: 통신·데이터 모듈

공통 명세서 v1.0 기준. 브랜치: `feat/data-sync`
**입력 UI·참가자 진행 UI·순서 배정 계산은 포함하지 않습니다.** 이 문서의 "호출 인터페이스"를 1·2·4번과 합의한 뒤 통합합니다.

> ⚠️ 상태: 코드 작성 완료, **아직 컴파일·실기기 검증 전**입니다.
> T3(통신) 20회 검증을 통과하기 전까지 "구현 완료"로 표시하지 않습니다(명세서 2장 원칙 4).

---

## 1. 파일 구성

```
shared/  (phone·wear 공용 라이브러리)
  model/Enums.kt            조건·상태·이벤트 유형
  model/Ids.kt              P001 / P001-C3-T04 / P001-C3-T04-A01 형식
  model/Records.kt          TrialConfig, TrialEvent, TrialResult, AttemptRecord
  metrics/TrialMetrics.kt   10장 측정 지표 공통 계산 함수 (두 기기 동일)
  recorder/AttemptRecorder  ★ 1·2·4번이 호출하는 기록 인터페이스
  db/                       Room DB (participants, attempts, events, conflicts, sync_log, backup_log …)
  db/ExperimentRepository   저장·중복·충돌·복구 로직
  sync/SyncProtocol.kt      메시지 경로·DTO·해시 (통신 규격 v1)
  sync/WearMessenger.kt     MessageClient 래퍼
  export/CsvExport.kt       trials.csv / events.csv / … + ZIP
  test/SharedLogicTest.kt   측정·ID·해시 단위 테스트
mobile/
  sync/PhoneSyncClient      ★ 4번이 호출: 설정 전송, 동기화 게이트, 결과 대기, 긴급 중단
  sync/PhoneSyncService     워치 메시지 수신 → DB 커밋 → ACK
  sync/PhoneDataBootstrap   앱 시작 시 복구
  data/IntegrityChecker     14장 무결성 검사 9항목
  data/ExportManager        15장 자동 백업·수동 내보내기 (MediaStore)
  debug/CommTestActivity    Phase 1 T3 검증 화면
wear/
  sync/WearSyncManager      ★ 워치 4번 UI가 호출: 설정 수신, 기록기 생성, 자동 재전송
  sync/WearSyncService      스마트폰 메시지 수신
```

---

## 2. 호출 인터페이스 (1·2·4번과 합의할 부분)

### 2-1. 4번 → 시행 설정 만들기
```kotlin
val no = repo.nextAttemptNo("P001", ConditionId.C3, "T04")           // 재시도면 2, 3…
val cfg = TrialConfig(
    attemptId = Ids.attemptId("P001", ConditionId.C3, "T04", no),
    participantId = "P001", condition = ConditionId.C3, taskId = "T04",
    attemptNo = no, phase = TrialPhase.MAIN,
    startVolume = 50, targetVolume = 70, orderIndex = 6,
)
```
과제 숫자·순서는 4번이 명세서 6·7장대로 계산합니다. 3번은 받은 값을 저장·전달만 합니다.

### 2-2. 참가자 등록 (ID 순차 발급 + 순서 고정 저장)
```kotlin
val p = repo.registerParticipant { seq, pid ->
    ExperimentRepository.ParticipantPlan(groupId = ..., conditionOrder = ..., taskOrders = ...)  // 4번 계산
}
repo.taskOrdersOf("P001")   // 재실행·복구 시 저장값 그대로 읽기 (재생성 금지)
```

### 2-3. 스마트폰 조건 C1·C2
```kotlin
val rec = AttemptRecorder(cfg, RepositoryAttemptStore(ExperimentRepository.get(ctx)))
rec.start()                       // 4번: 카운트다운 종료 시 (값 공개·오디오·타이머와 같은 시점)
rec.inputDown(mapOf("keyCode" to "24", "repeat" to "0"))   // 1번
rec.valueChange(55, mapOf("keyCode" to "24"))               // 1·2번: 실제로 값이 바뀐 직후만
rec.inputUp()
rec.complete() / rec.timeout() / rec.invalidate("ADMIN_ABORT")   // 4번
```

### 2-4. 워치 조건 C3·C4
스마트폰(4번):
```kotlin
val sync = PhoneSyncClient.get(ctx)
val gate = sync.checkReadyForNextWatchTrial()      // ready=false 면 시작 금지 + gate.reasons 관리자 표시
val r = sync.sendConfig(cfg)                       // Acked(accepted=true) 일 때만 진행
val saved = sync.awaitWatchResult(cfg.attemptId)   // 워치 시행 종료 + 스마트폰 저장 완료
sync.sendAbort(cfg.attemptId)                      // 긴급 중단
```
워치(4번 워치 UI, 2번 입력):
```kotlin
val mgr = WearSyncManager.get(ctx); mgr.onAppStart()        // 앱 시작 시 1회
mgr.pendingConfig.collect { cfg -> /* 준비 화면 */ }
val rec = mgr.newRecorder(cfg)                               // 이후 사용법은 2-3과 동일
mgr.abortRequests.collect { if (it.attemptId == rec.config.attemptId) rec.invalidate(it.reason) }
```
`abortRequests` 는 마지막 요청 1건을 다시 전달하므로 **반드시 attemptId 를 비교**해야 합니다.

### 2-5. 참가자 종료 (4번)
```kotlin
val r = ExportManager(ctx).finishParticipantAndBackup("P001")
// r.success(백업)와 r.validationPassed(검증)를 별도로 표시
```

### 2-6. 앱 시작 (스마트폰)
```kotlin
PhoneDataBootstrap.onAppStart(ctx) { invalidatedIds -> /* 관리자에게 재시도 대상 표시 */ }
repo.finishedLogicalTaskIds("P001")   // 재개 지점 결정용 (COMPLETED·TIMEOUT 끝난 과제)
```

---

## 3. 설계 결정 (명세서 대응)

| 명세서 | 구현 |
|---|---|
| 10.2 통신 시간 미포함 | 시각은 **시행 기기의** `SystemClock.elapsedRealtimeNanos()` 로 호출 즉시 기록. 저장·전송은 이후 백그라운드 |
| 11.1 임의 생성 금지 | 기록기는 받은 이벤트만 저장. 값 보정·중간값 생성 없음. 범위 위반값도 그대로 저장 후 무결성 검사에서 표시 |
| 12.3 중복·충돌 | 시도 ID + 내용 SHA-256. 동일 → DUPLICATE, 다름 → `conflicts` 테이블에 보존, 기존 기록 미변경 |
| 13.2 연결 오류 | 워치는 먼저 로컬 커밋 → 전송. 스마트폰은 **DB 커밋 후에만** `persisted=true` ACK. 워치는 ACK 해시가 일치할 때만 SYNCED, 원본은 삭제하지 않음 |
| 13.2 재전송 | 재연결(Capability 변경), 앱 시작, 결과 저장 직후, 2→30초 백오프 반복, 스마트폰의 flush 요청 |
| 13.2 다음 시행 차단 | `checkReadyForNextWatchTrial()` + 워치도 미전송 건이 있으면 새 설정을 `REJECTED_PENDING_SYNC` 로 거부(이중 안전) |
| 13.3 앱 종료 | START 시 IN_PROGRESS 행 저장 → 재시작 시 INVALID(`APP_TERMINATED_DURING_TRIAL`) 확정. 이벤트를 꾸며 넣지 않음 |
| 15 백업 | MediaStore `Documents/HCI_Experiment/Backups`(자동), `.../Exports`(수동). 같은 이름 있으면 `_2` 접미사 |
| 18 자동 삭제 금지 | DAO 에 DELETE 쿼리 없음. 충돌은 '해결 표시'만 가능 |

ZIP 구성: `trials.csv`, `events.csv`, `participants.csv`, `conflicts.csv`, `integrity_report.csv`, `sync_log.csv`

`trials.csv` 의 `completion_time_ms` 는 COMPLETED 일 때만 값이 있고, TIMEOUT 은 `elapsed_ms` 만 기록합니다(8.2: 30초로 취급하지 않음).

---

## 4. 팀에 확인이 필요한 사항

1. **패키지명·applicationId**: 코드는 `com.hci.teamp` 로 작성. 저장소 기준에 맞춰 일괄 변경 필요.
2. **서명 키**: Data Layer 는 phone·wear 가 같은 applicationId·같은 서명일 때만 통신됨 → 공용 debug keystore 필요.
3. **수동 내보내기 폴더**: 명세서에 경로가 없어 `Documents/HCI_Experiment/Exports` 로 정함. 합의 필요.
4. **중단 시 이벤트 손실**: 진행 중 이벤트는 메모리에 있다가 종료 시 한 번에 저장됨. 앱이 시행 중 강제 종료되면 해당 시도는 INVALID + START 만 남음(재시행 대상이라 분석 영향 없음). 중간 저장이 필요하면 알려 주세요.
5. **TIMEOUT 타이머**: 4번이 30초 타이머를 돌리고 `rec.timeout()` 호출하는 것으로 가정.

---

## 5. Phase 1 T3 검증 절차 (각 20회)

| 항목 | 방법 | 통과 기준 |
|---|---|---|
| 송수신 | CommTestActivity → Ping 20회 | 20/20 성공, 해시·길이 일치 |
| 설정 전송 | 4번 테스트 화면에서 `sendConfig` 20회 | 20/20 ACCEPTED, 같은 ID 재전송 시 DUPLICATE |
| 결과 수신 | 워치에서 테스트 시도 20회 종료 | 스마트폰 trials/events 수가 워치와 일치, 워치 SYNCED |
| 끊김 후 복구 | 워치 시행 중 블루투스 끄기 → 종료 → 켜기, 20회 | 진행 중 시행 정상 종료, 재연결 후 자동 수신, 게이트 차단→해제 확인 |
| 중복 방지 | 같은 결과 재전송(flush 반복) | 스마트폰 행 수 변화 없음, DUPLICATE 로그 |

결과는 `sync_log.csv` 와 Logcat(`HCI-PhoneSync`, `HCI-WearSync`, `HCI-Data`)으로 확인하고, 실패 시 원인을 기록한 뒤 다시 검증합니다.
