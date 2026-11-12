package dev.picasso.middleware

/** 키를 순서대로 붙이는 최소한의 것. 키를 빼는 길이 없는 것이 이 클래스의 요점이다. */
internal class Obj {
    private val sb = StringBuilder("{")
    private var first = true

    private fun key(name: String): Obj {
        if (!first) sb.append(',')
        first = false
        sb.append(Json.quote(name)).append(':')
        return this
    }

    fun str(name: String, value: String?): Obj =
        key(name).also { sb.append(if (value == null) "null" else Json.quote(value)) }

    fun num(name: String, value: Number): Obj = key(name).also { sb.append(value) }

    fun bool(name: String, value: Boolean): Obj = key(name).also { sb.append(value) }

    /** 이미 JSON 인 값. */
    fun raw(name: String, value: String): Obj = key(name).also { sb.append(value) }

    fun done(): String = sb.append('}').toString()
}

/**
 * JSON 문자열 한 줄. **사람이 적은 글이 여기로 나간다** — 진단 사유와 검토 의견이 그렇다.
 * 따옴표 하나만 새어도 그 줄이 통째로 못 읽히고, 읽는 쪽에는 «한 줄이 사라졌다» 로만 보인다.
 */
internal object Json {

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                // 제어 문자는 그대로 두면 JSON 이 아니다. 한글은 UTF-8 로 그대로 나간다.
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
