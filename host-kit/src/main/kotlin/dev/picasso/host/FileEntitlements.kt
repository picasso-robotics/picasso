package dev.picasso.host

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.middleware.DeclaredAction
import dev.picasso.middleware.Entitlement
import dev.picasso.middleware.Entitlements
import dev.picasso.middleware.Revocation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 자격 선언 목록을 **파일에서** 읽는다 — ADR 43 이 «배치의 결정» 이라 한 자리의 첫 구현체.
 *
 * 값이 설정 파일에 있든 레지스트리에 있든 미들웨어의 코드는 같다. 그래서 이것은 포트의 구현체이지
 * 위치의 결정이 아니며, 레지스트리가 담는 날 이 클래스만 대체된다.
 *
 * ## 못 읽으면 던진다
 *
 * **빈 목록으로 접지 않는다.** 접으면 오타 난 선언 파일이 «아무것도 선언 안 했다» 와 같은 모양이 되고,
 * 그 배치는 자동 승인이 전부 거절되는 것을 정상으로 읽는다. 기동 때 크게 실패하는 편이 낫다.
 *
 * 빈 목록 자체는 정상이다 — «아무것도 안 덮는다» 는 유효한 선언이고 사람은 여전히 누를 수 있다.
 */
class FileEntitlements private constructor(private val rows: Map<String, Entitlement>) : Entitlements {

    override fun declaredFor(approverId: String): Entitlement? = rows[approverId]

    /** 선언된 승인자들. 기동 로그가 무엇을 읽었는지 말하게 하는 데 쓴다. */
    fun approvers(): Set<String> = rows.keys

    companion object {

        fun read(path: Path): FileEntitlements {
            val text = try {
                Files.readString(path)
            } catch (e: Exception) {
                throw IllegalArgumentException("자격 선언 목록을 못 읽었다: $path", e)
            }
            val root = try {
                Struct.newBuilder().also { JsonFormat.parser().merge(text, it) }.build().fieldsMap
            } catch (e: Exception) {
                throw IllegalArgumentException("자격 선언 목록이 JSON 이 아니다: $path", e)
            }
            val declared = root["entitlements"]
                ?: throw IllegalArgumentException("선언 목록에 «entitlements» 가 없다: $path")
            if (!declared.hasListValue()) throw IllegalArgumentException("«entitlements» 가 목록이 아니다: $path")

            val rows = declared.listValue.valuesList.map { one ->
                if (!one.hasStructValue()) throw IllegalArgumentException("선언 하나가 객체가 아니다: $path")
                entitlement(one.structValue.fieldsMap)
            }
            // 같은 승인자를 두 번 적으면 어느 쪽이 유효한지 파일이 답하지 않는다. 고르지 않고 거부한다.
            val byId = rows.associateBy { it.approverId }
            require(byId.size == rows.size) { "같은 승인자를 두 번 선언했다: ${rows.map { it.approverId }}" }
            return FileEntitlements(byId)
        }

        private fun entitlement(fields: Map<String, Value>): Entitlement = Entitlement(
            approverId = string(fields, "approverId"),
            actions = list(fields, "actions").map { action ->
                if (!action.hasStructValue()) throw IllegalArgumentException("조치 하나가 객체가 아니다")
                val inner = action.structValue.fieldsMap
                DeclaredAction(
                    skillType = string(inner, "skillType"),
                    parameters = inner["parameters"]?.let { parameters ->
                        if (!parameters.hasStructValue()) throw IllegalArgumentException("«parameters» 가 객체가 아니다")
                        parameters.structValue.fieldsMap.mapValues { (key, value) ->
                            if (!value.hasStringValue()) throw IllegalArgumentException("«$key» 의 값이 문자열이 아니다")
                            value.stringValue
                        }
                    }.orEmpty(),
                )
            },
            robotIds = list(fields, "robotIds").map {
                if (!it.hasStringValue()) throw IllegalArgumentException("«robotIds» 의 원소가 문자열이 아니다")
                it.stringValue
            }.toSet(),
            expiresAt = Instant.parse(string(fields, "expiresAt")),
            // **없으면 널이고, 있으면 세 칸을 다 요구한다**(ADR 45). 반쯤 적힌 철회를 받아 주면
            // 「언제·누가·왜」 중 빠진 것이 빈 문자열로 나가고, 읽는 쪽은 그것을 답으로 읽는다.
            revocation = fields["revocation"]?.let { revoked ->
                if (!revoked.hasStructValue()) throw IllegalArgumentException("«revocation» 이 객체가 아니다")
                val inner = revoked.structValue.fieldsMap
                Revocation(
                    at = Instant.parse(string(inner, "at")),
                    by = string(inner, "by"),
                    reason = string(inner, "reason"),
                )
            },
        )

        private fun string(fields: Map<String, Value>, name: String): String {
            val value = fields[name] ?: throw IllegalArgumentException("선언에 «$name» 이 없다")
            if (!value.hasStringValue()) throw IllegalArgumentException("선언의 «$name» 이 문자열이 아니다")
            return value.stringValue
        }

        private fun list(fields: Map<String, Value>, name: String): List<Value> {
            val value = fields[name] ?: throw IllegalArgumentException("선언에 «$name» 이 없다")
            if (!value.hasListValue()) throw IllegalArgumentException("선언의 «$name» 이 목록이 아니다")
            return value.listValue.valuesList
        }
    }
}
