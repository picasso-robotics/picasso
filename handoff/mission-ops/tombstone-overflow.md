# 놓은 작업 지시 표시의 넘침 선언과 놓는 입구(§15.324)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. 단 아래 «판 올림과 같은 PR» 은 순서가 아니라 조건이다 — 따로 들이면 그 사이에 호스트가 선다.

## 바뀐 것
- 놓은 작업 지시 표시(`forget` 이 남기는 작업 지시 id 마다의 표시)가 `Retention.forgottenOrders`(기본 100,000)에 차면 **기본으로 더 놓지 않는다.** `forget` 이 실행·작업 응답을 건드리기 전에 `ForgetOutcome.Refused`(사유 «놓은 작업 지시 표시가 상한에 찼다 …»)를 내고, pump 끝의 실행 표 정리도 그 라운드에 멈춘다. 전에는 오래된 표시부터 조용히 내렸고, 내린 id 의 재제출은 이 층에서 새 실행으로 섰다.
- 새 칸 `Retention.forgottenOverflow: ForgottenOverflow`(`REFUSE` 기본, `EVICT`). `EVICT` 는 전의 동작(오래된 표시부터 내리고 `RetentionEvictions.forgottenOrders` 로 셈)이다. **이 선언은 약속이다** — 담는 쪽이 작업 지시 id 유일성을 제 영속 기록으로 보증하고, 같은 id 의 접수(`submit`·`adopt`·`resume`)와 승인을 picasso 를 부르기 전에 거부한다.
- 새 입구 `Middleware.releaseForgotten(jobOrderId): Boolean` — 표시를 지운다(참 = 이번에 지움, 거짓 = 지금 들고 있지 않음). 같은 계약 아래에서만 부르고, id 재사용의 허가가 아니다.
- 새 읽기 `Middleware.forgottenOrders(): ForgottenOrdersState` — 든 표시 수·상한·넘침 선언·찬 표 때문에 거부한 놓기 수·지운 수(인스턴스 수명). `RetentionEvictions` 는 그대로 실제로 내린 수만 센다.

## 지을 코드
- **판 올림과 같은 PR 에 `Middleware(…, retention = Retention(forgottenOverflow = ForgottenOverflow.EVICT))` 를 선언한다.** 호스트는 이미 실행 일지로 picasso 메모리에 없는 같은 id 를 거부하므로(`journalRefusal`, 재기동 전후) 약속을 지킨다. 선언하지 않으면 놓은 작업 지시 10만 건 뒤부터 `forgetSettled` 가 pump 마다 `Refused` 를 받아 경고가 라운드마다 쌓이고, 호스트 쪽 맵(`dropForgotten` 이 지우던 것)과 picasso 실행 표가 상한을 넘어 계속 는다.
- **`releaseForgotten` 은 부르지 않는다.** `EVICT` 아래에서 남은 최근 표시는 일지 다음의 둘째 방어선이다 — 놓기마다 지우면 그 방어선을 스스로 버린다. 거부 수를 보려면 `forgottenOrders().refusedForgets` 를 읽는다(`EVICT` 면 0 이어야 한다). 찼는지는 `held == cap` 으로 본다.
- **필수 호스트 회귀시험.** 호스트는 지금 `Middleware(...)` 에 `retention` 을 넘기지 않고 미들웨어도 밖에 열지 않으므로, 먼저 `Retention` 을 호스트 생성 인자(시험이 작은 상한을 넣을 칸)로 받고 `retentionEvictions()`·`forgottenOrders()` 를 시험이 읽을 자리를 연다. 그 위에서 작은 `forgottenOrders`(예: 1)와 `EVICT` 로 호스트를 세우고, 작업 지시 A·B 를 끝내 놓아 **A 의 표시가 실제로 내려간 것**(`retentionEvictions().forgottenOrders == 1`, `forgottenOrders().held == 1`)을 확인한 뒤:
  - A 를 호스트로 재제출 → 일지가 `FORGOTTEN_JOB_ORDER` 로 시작하는 사유로 거부, picasso 실행 표에 A 의 새 실행 없음, 기체에 새 명령 없음.
  - A 에 서 있던 제안(다른 기체에서 끝난 주문의 미소모 제안)의 값 없는 승인(호스트 `attemptApproval`) → 같은 단언(호스트가 `journalRefusal` 로 `REMEDY_NOT_APPLIED` 를 내고 새 실행·명령 없음). picasso 는 표시가 없으면 이 승인을 조치 열을 단 새 실행으로 받는다 — 막는 것은 호스트의 몫이다.

## 영향 없음(확인)
- narrator·recovery: 표시는 내보내기·판단 기록에 실리지 않는다. 노트 없음.
- 내보내기·판단 기록·작업 응답의 형식은 그대로다.

## 이 노트가 닫지 않는 것
- 표시는 메모리라 재기동 뒤에는 없다 — 그 재전송은 지금처럼 호스트의 일지가 막는다(한계 레지스터 «§15.166» 행).
