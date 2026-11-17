# 제어자 세대 거절의 새 자유 값 둘 — 판 그대로(ADR 54, §15.330)

받는 쪽: incident-recovery. 반입 시점과 방법은 받는 쪽이 결정한다.

## 바뀐 것
- 판은 그대로다 — 내보내기 10, 판단 기록 6. 칸은 늘지 않았다.
- 기존 자유 문자열 칸에 새 값 둘이 실릴 수 있다.
  - 인시던트 근거 창 `evidenceWindow[].kind` = `CONTROLLER_REFUSED` — 수신자가 제어자 세대로 명령을 거절했거나(«수신자가 답한 세대 거절») 응답 머리말이 더 높은 바닥값을 알렸다(«응답 머리말의 더 높은 바닥값»). `detail` 은 사람이 읽는 한 줄이고 기체·까닭(`EPOCH_MISSING`·`OUT_OF_RANGE`·`BELOW_FLOOR`·`NOT_ADMITTED`)·요청 세대·바닥값·시각이 든다.
  - 판단의 단위 메모(`verdict.note`)에 `refused by the receiver's controller-epoch fence; not applied, no failure judged` 가 들 수 있다.

## 읽는 쪽이 할 일
- **분기할 칸이 아니다.** 문장으로 옮기면 충분하다. `CONTROLLER_REFUSED` 는 실패·취소·완료가 아니고 인시던트를 열지 않는다 — 밀려난 프로세스(`BELOW_FLOOR`)는 판단을 멈추고, 설정 오류(나머지 셋)는 그 명령만 거절된 것이다.
- 증상·재발로 세지 않는다. 세대 거절은 하위가 명령을 받지 않았다는 확실한 답이다.
