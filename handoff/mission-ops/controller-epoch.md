# 수신 측 제어자 세대 대조 — 계약 0.12.0(ADR 54, §15.330)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. 아래는 picasso 가 낸 표면과 그 뜻이다.

## 바뀐 것
- 계약 0.12.0(추가 전용). 헤더 `controller_epoch` — 요청은 발신 세대, 응답은 수신자가 든 그 기체의 바닥값(0 = 대조 안 함, 옛 수신자와 같은 뜻). 거절 `REJECTION_CODE_STALE_CONTROLLER`(+ `Rejection.requested_controller_epoch`·`controller_epoch_floor`). `GetSnapshotResponse.fence_status` — `UNSPECIFIED`(인수 확인 아님)·`VOLATILE`·`DURABLE_PENDING`·`DURABLE_CONFIRMED`.
- 판단 기록 6·내보내기 10 은 그대로다. 세대는 멱등 키·장부에 들지 않고, 판단 기록·내보내기에도 세대 칸은 없다. 다만 기존 자유 문자열 칸에 새 값 둘이 실릴 수 있다 — 실행 자취 `CONTROLLER_REFUSED`(세대·바닥값이 든 문장)가 인시던트 근거 창 `evidenceWindow[].kind/detail` 로, 단위 메모 `FENCE_REFUSED` 가 판단 기록 `verdict.note` 로. 분기할 칸이 아니다.

## 쓸 표면
- `PicassoClient(channel, clientId, identityOverride, deadlineSeconds, controllerEpoch: () -> Long)` — 처음 본 0 아닌 값을 붙잡는다. 공급자가 바뀌면 명령을 보내지 않는다(`ControllerEpochChanged`). 세대가 바뀌면 클라이언트·포트·`IssuingRobotPort` 를 새로 만든다. `PicassoClient.epoch` 이 붙잡은 값이다.
- `ClientRobotPort(client, now, backoff, onSuperseded: ClientRobotPort.Superseded)` — `superseded(robotId, mine, floor)` 를 **실제로 밀려났을 때만**(내 세대 > 0 이고 바닥값 > 내 세대 — `BELOW_FLOOR` 거절, 또는 읽기·스트림·능력 응답 머리말) 한 번 부른다. 그 뒤 명령은 `CommandNotSent`. 세대 0·범위 밖·자격 없음 거절은 그 명령만 거절되고 닫히지 않는다. `ClientRobotPort.superseded`(문장)·`controllerRefusal(robotId)`(`ControllerRefusal`)가 래치다. `PicassoClient.floorSeen(robotId)` 는 지금까지 본 가장 큰 바닥값. LOST 판단은 담는 쪽이다.
- 거절 까닭: `StaleReason.of(requested, floor)` — `EPOCH_MISSING`·`OUT_OF_RANGE`·`BELOW_FLOOR`(밀려남)·`NOT_ADMITTED`. 세대 범위는 1..2^63-1.
- 스냅숏: `RobotSnapshot.controllerFloor`·`fenceStatus`(포트 읽기 결과에 실린다).
- 미들웨어는 세대 거절을 실행 자취 `CONTROLLER_REFUSED` 로 한 번 남긴다. 밀려남이면 그 기체의 새 발행을 `AUTHORITY_NOT_HELD` 로 막고 미확정 유예·인시던트 판단을 멈춘다. 밀려남이 아니면(설정 오류) 출발 다시 내기를 `LOOKUP_INTERVAL` 간격으로 늦출 뿐 정상 경로(유예 → 운영자 보류)를 막지 않는다.
- `Middleware(..., authority: AuthorityPort?)` — `AuthorityPort.read(robots): Map<String, Authority>`(`Authority.Held` / `Authority.NotHeld(reason)`). 답에 없음·던짐은 막음(`PermitCause.AUTHORITY_NOT_HELD`, `Execution.permitBlock`). 새 발행 셋만 막고 취소는 막지 않는다.
- 수신자 설정: `AdapterHost(..., fence = ControllerFence(FenceMode.FENCED, FileFenceStore(dir)), fenceWait)`, 미믹 `RobotRegistry(..., fence)`. 기본은 `UNFENCED`.

## 할 일(3단계)
- 리스 세대를 공급자로 넣고 `onSuperseded` 를 `HostLeadership.lost` 로 잇고 `AuthorityPort` 를 구현한다.
- 확인 답의 `DURABLE_PENDING` 은 기한 초과 또는 결과를 모르는 변경 호출(접수·갱신·조작)이다. 접수·갱신은 풀리지 않고, 조작은 어댑터가 벤더 관측이라 말하는 펌프 읽기로 그 태스크의 종착을 볼 때만 풀린다 — 그 기체는 운영자 확인으로 간다(한계 대장 «§15.330 · 미확정 접수»).
- 만료 인수 `HOLDING` 에서 기체마다 내 세대로 `GetSnapshot` — 응답 머리말 바닥값 = 내 세대 ∧ `DURABLE_CONFIRMED` 인 기체만 연다. 재대조는 그 응답의 스냅샷으로 한다. 바닥값이 더 높으면 LOST. 0·`UNSPECIFIED`·`VOLATILE`·`DURABLE_PENDING` 은 운영자 확인을 유지한다.
- 전제(코드가 집행 못함): 한 기체의 수신자는 하나, 직접 벤더 접근 없음, 수신자는 인증된 입구 뒤, 승격 때 세대를 수신자의 바닥값보다 높게 올림(한계 대장 «§15.330» 행들).
- 플릿·셀 HTTP 경로는 picasso 계약 밖이다.
