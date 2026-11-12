package dev.picasso.middleware

// «한 번만 알림»(재설계 R2, 설계 일지 §15.302). 같은 사실을 라운드마다 되풀이하면 운영자가 곧 무시한다. 그래서 알린 자리마다 마지막으로
// 알린 값을 들고, 같은 값이면 다시 내지 않는다. 앞에서는 자리마다 필드나 맵이 하나씩 있었다(실행에 아홉). 새 자리는 [Notice] 에 열쇠
// 하나를 더해 붙는다 — 필드를 늘리지 않는다.

/** 알린 자리. 열쇠가 같으면 같은 자리다. */
internal sealed interface Notice {
    /** 지연 보고(플릿 운반이 늦다)를 낸 단위. */
    data object Delay : Notice

    /** 운영자 보류를 알린 단위. */
    data object Hold : Notice

    /** 미확정을 알린 단위. */
    data object Doubt : Notice

    /** 다음 단위를 막는 결함을 알린 열쇠 열. */
    data object Block : Notice

    /** 마지막 작업 응답이 상위에 보인 막음·보류·미확정([Middleware.noteAttention]). */
    data object Attention : Notice

    /** 라운드 진행과 기체 동기화([source])에서 포트가 던진 마지막 까닭. 그 자리가 한 번 성공하면 걷는다. */
    data class PumpFailure(val source: String) : Notice

    /** 기체마다 남긴 마지막 시계 차의 방향·크기 등급. 시계 차가 허용 안으로 돌아오면 걷는다. */
    data class ClockSkew(val robot: String) : Notice

    /** 설비 대기가 자취에 남긴 마지막 신호 값 — 태스크 id(시도마다 다르다)로 단다. */
    data class Signal(val taskId: String) : Notice

    /** 지연 이벤트(15.1)로 적은 (단위, 리비전, 하위 상태). 같은 것은 한 번만 적는다. */
    data class Late(val unitId: String, val revision: Int, val state: String) : Notice
}

/** 실행 하나의 «알린 것» — [Notice] 마다 마지막으로 알린 값. */
internal class Noticed {
    private val last = mutableMapOf<Notice, Any>()

    /** [key] 에 [value] 를 알린다. 앞과 같은 값이면 거짓이다 — 부르는 쪽은 그때 다시 내지 않는다. */
    fun note(key: Notice, value: Any): Boolean = last.put(key, value) != value

    operator fun get(key: Notice): Any? = last[key]

    /** 걷는다 — 다음에 같은 값이 서면 다시 알린다. */
    fun clear(key: Notice) {
        last.remove(key)
    }

    /** [value] 를 이미 알린 것으로 둔다. 널이면 걷는다. */
    fun set(key: Notice, value: Any?) {
        if (value == null) last.remove(key) else last[key] = value
    }
}
