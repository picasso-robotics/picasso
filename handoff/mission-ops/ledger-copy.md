# 두 대장의 사본 선언과 줄 단위 인코딩(§15.327)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. 한계 레지스터 «§15.166» 은 소유자를 담는 쪽(mission-ops 실행 호스트, 보관·조회)으로 고쳤고 picasso 몫은 끝났다 — 이 노트의 반입이 그 행을 닫는다.

## 바뀐 것
- 새 칸 `Retention.copyAck: CopyAck?`(기본 널 = 꺼짐). 켜면 인시던트 번들·탐색 기록은 담는 쪽이 커밋을 확인하기 전에는 보존 상한(`Retention.incidents`·`remedySearches`)이 내리지 않는다. 구동 중 실행 번들 예외와 같은 자리이고 상한은 그만큼 넘는다. **끄면 동작·바이트가 지금과 같다.**
- `CopyAck(pendingIncidents = null, pendingSearches = null)` — 대장마다 확인 대기 한도, 널이면 그 대장의 상한(인시던트 2,000·탐색 10,000). 기본에서는 선언이 메모리를 상한 위로 올리지 않고 내릴 때 확인한 줄을 먼저 고르게만 한다. 상한 위로 더 지키려면 한도를 크게 준다. 구동 중 실행의 번들은 한도·상한과 무관하게 남는다(기존 예외). 넘으면 가장 오래 기다린 줄부터 보호가 풀리고, 풀린 줄이 확인 전에 내려가면 «확인 전 내림» 으로 센다(내림은 `retentionEvictions()` 에도 그대로 센다).
- 새 입구 `Middleware.ackCopiedIncidents(incidentIds): Int`·`ackCopiedSearches(searchIds): Int` — 대장마다 따로(인시던트 id 와 탐색 id 를 섞지 않는다), 멱등, 모르는 id 무시, 새로 확인한 수를 돌려준다. 선언이 꺼져 있으면 늘 0. 확인한 줄은 곧바로가 아니라 다음 정리(봉인 끝·다음 탐색 기록)에서 상한대로 내린다. **확인 뒤 검토·판단으로 인시던트 줄이 바뀌면 그 id 가 다시 확인 대기로 올라간다** — 개정 줄을 다시 적고 다시 확인한다. 탐색 기록은 적힌 뒤 바뀌지 않는다. 다른 입구처럼 `pump` 와 같은 스레드에서 부른다(picasso 는 잠그지 않는다).
- 새 읽기 `Middleware.copyAcks(): CopyAckState` — 선언과 대장마다 `pending`(확인 대기)·`overCap`(상한을 넘은 정도)·`exposed`(한도로 보호가 풀린 누계)·`evictedBeforeCopy`(확인 전 내림 누계).
- 줄 단위 인코딩 `LedgerExport.incidentLine(bundle)`·`remedySearchLine(record)` — 줄바꿈 없는 한 줄, `incidents(…)`·`remedySearches(…)` 의 한 줄과 바이트가 같다. 줄은 판을 싣지 않으므로 `LedgerExport.LINE_SCHEMA_VERSION`(지금 `"9"`, `SCHEMA_VERSION` 과 함께 오름)을 줄 곁에 적는다. 목록 인코딩은 그대로다.
- **보장은 «프로세스가 사는 동안 수집 전에 잃지 않는다» 까지다.** 확인한 줄을 picasso 로 되돌려 넣는 복원 입구는 없다.

## 지을 코드
- **선언 켜기.** `Middleware(…, retention = Retention(…, copyAck = CopyAck()))`. 한도는 사본 수집 주기에 쌓이는 줄 수(개정 줄 포함)보다 넉넉하게.
- **커밋 뒤 확인.** 사본 표에 줄을 적은 DB 트랜잭션이 커밋된 **뒤에** 그 id 로 `ackCopiedIncidents`·`ackCopiedSearches` 를 부른다. 커밋 전에 부르면 그 사이에 죽을 때 상한이 줄을 내려 사본에서 빠진다.
- **줄 그대로 적기.** 사본 표에는 `incidentLine`·`remedySearchLine` 의 문자열과 `LINE_SCHEMA_VERSION` 을 그대로 적는다. **줄에는 instanceId 가 없다** — `Middleware.instanceId` 를 곁에 적는다. **`WITHHELD` 줄에는 `steps` 키가 없다** — 제안 표에서 걸음을 꺼내 줄을 조립하거나 채우지 않는다(가림이 조회 한 번으로 풀린다).
- **확인 대기 감시.** `copyAcks()` 를 주기로 읽어 `pending`·`overCap` 이 늘기만 하면 수집이 멈춘 것이다. `evictedBeforeCopy` 가 0 이 아니면 그만큼 사본에 빠졌을 수 있다 — 경보 대상이다.

## 합의의 mission-ops 몫
- **지금 있는 결함 셋.**
  - 사본 수집이 개수(`incidents.drop(copiedIncidents)`)로 센다 — 상한이 앞 줄을 내리면 어긋난다. 식별자 기준으로 바꾼다.
  - 파일 게시가 DB 커밋보다 먼저다 — 게시를 커밋 뒤로 옮긴다.
  - 일반 승인 경로의 일지·판단 기록이 한 트랜잭션이 아니다 — `journalRecovery` 선례처럼 한 트랜잭션으로 묶는다.
- 탐색 사본 표 — 덧붙이기만, 일지 FK 금지, 유일 (instance_id, search_id).
- 인시던트 사본에 내보내기 줄 칸 — 검토·판단이 바뀌면 같은 인시던트 id 의 줄을 다시 받아 적고(개정) 다시 확인한다. 바뀐 id 는 `copyAcks().incidents.pending` 에 다시 잡힌다.
- 승인 사실 표와 합산 — 같은 조치의 승인 횟수의 정본은 담는 쪽 합산이다(기체 + 순서 있는 조치 유형 열, 승인자 종류 합침, 재시도·실행 복원 중복 집계 없음). picasso 의 `repeatedRemedies` 는 «이 인스턴스 안의 수» 다.
- 커밋 뒤 `ackCopied*`(위).
- 이전 인스턴스의 탐색·승인 횟수 읽기 — 재기동 뒤에는 사본 표에서 읽는다.

## 넣지 않은 것(picasso)
- 복원 입구 — 재기동 뒤 승인을 다시 쓰는 경로가 없다(다시 지은 실행은 판단 기록의 승인으로 소모, 복원하지 않은 작업 지시는 호스트 일지가 `REMEDY_NOT_APPLIED`, 제안이 없으면 `NO_PROPOSAL`).
- 서 있는 제안의 영속(가림 위험), 툼스톤의 추가 영속.

## 영향 없음(확인)
- narrator·recovery: 내보내기·판단 기록의 형식은 그대로다. 노트 없음.

## 이 노트가 닫지 않는 것
- 이빅션한 실패 실행을 판단 기록으로 다시 지어 재작업 받기 — 한계 레지스터 «§15.327 · 실패 실행 재작업».
