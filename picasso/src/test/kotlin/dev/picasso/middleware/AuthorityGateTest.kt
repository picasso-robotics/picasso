package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **발행 관문의 첫째 입력 — 유효 권위**(ADR 51 결정 6, ADR 54). 담는 쪽이 기체마다 «쥠/아님» 을 답하는 포트([AuthorityPort])를 붙이면,
 * 쥐지 않은 기체(포트가 던짐 포함)에는 새 로봇 명령을 내지 않고 기다린다. 포트가 없으면 오늘 거동이다. 취소는 막지 않는다.
 */
class AuthorityGateTest {

    private val now = Instant.parse("2026-09-06T00:00:00Z")

    @Test
    fun `포트가 없으면 막지 않는다`() {
        assertIs<IssueVerdict.Issue>(IssuingGate(null, null, { now }).check(setOf("r1")))
    }

    @Test
    fun `쥐지 않은 기체와 답에 없는 기체는 AUTHORITY_NOT_HELD 로 막는다`() {
        val port = AuthorityPort { robots -> robots.associateWith { if (it == "r1") Authority.Held else Authority.NotHeld("인수 확인 대기") } - "r3" }
        val verdict = assertIs<IssueVerdict.Withheld>(IssuingGate(null, null, { now }, port).check(setOf("r1", "r2", "r3")))
        assertEquals(listOf("r2", "r3"), verdict.robots.map { it.robotId })
        assertTrue(verdict.robots.all { r -> r.reasons.all { it.cause == PermitCause.AUTHORITY_NOT_HELD } })
        assertEquals("인수 확인 대기", verdict.robots.first().reasons.single().subject)
        assertIs<IssueVerdict.Issue>(IssuingGate(null, null, { now }, port).check(setOf("r1")))
    }

    @Test
    fun `포트가 던지면 막는다`() {
        val port = AuthorityPort { throw IllegalStateException("lease store down") }
        val verdict = assertIs<IssueVerdict.Withheld>(IssuingGate(null, null, { now }, port).check(setOf("r1")))
        assertEquals(PermitCause.AUTHORITY_NOT_HELD, verdict.robots.single().reasons.single().cause)
        assertEquals(WorkPermit.UNKNOWN, verdict.robots.single().permit)
    }

    @Test
    fun `권위를 쥐지 않으면 첫 출발을 내지 않고 기다리며 취소는 막지 않는다`() {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        harness.use { h ->
            var held = false
            val mw = Middleware(ClientRobotPort(h.client()), now = { h.clock.now() }, authority = { robots ->
                robots.associateWith { if (held) Authority.Held else Authority.NotHeld("넘겨받는 중") }
            })
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
            mw.pump()
            assertEquals(0, h.recorder.requests.count { it.second is StartTaskRequest }, "권위를 쥐지 않았는데 출발을 냈다")
            assertEquals(UnitState.PENDING, exec.units.first().state)
            assertEquals(PermitCause.AUTHORITY_NOT_HELD, exec.permitBlock?.robots?.single()?.reasons?.single()?.cause, "막은 까닭을 안 남겼다")

            held = true
            fun running() = h.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
                .any { it.taskState == "RUNNING" } && exec.units.any { it.state == UnitState.RUNNING }
            repeat(30) {
                if (running()) return@repeat
                mw.pump()
                h.advance(Duration.ofSeconds(10))
                mw.pump()
            }
            assertTrue(running(), "권위를 쥔 뒤에도 출발이 나가지 않았다")

            held = false
            mw.cancel(exec.executionId)
            mw.pump()
            assertTrue(h.recorder.requests.any { it.second is CancelTaskRequest }, "권위를 쥐지 않았다고 취소를 막았다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"

        fun order() = JobOrder(
            jobOrderId = "PATROL-9",
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}
