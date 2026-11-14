package dev.picasso.host

import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.RemedySearchRecord
import dev.picasso.middleware.ResultExport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * 두 대장과 안내를 **한 벌로 디스크에 놓는다.** 모양을 짓는 것은 [LedgerExport] 이고 담는 일이 여기다.
 *
 * ## 절차가 한 벌인 것이 요점이다
 *
 * ★★**앞 판은 이 절차가 셋이었다** — `ExportFixtureTest` 와 `LedgerExportTest` 와 창구가 같은 일을
 * 따로 적고 있었다. 셋이 각자 제 시험으로 초록이었으므로 **갈려도 아무도 안 빨개진다.** 실제로 창구
 * 쪽만 구동 식별자 고정과 건너뛰기를 얻어 이미 한 벌이 갈려 있었다(§15.186). §15.115 가 「두 벌로
 * 두면 어느 날 한쪽만 는다」고 적은 바로 그 모양이다.
 *
 * 그래서 절차를 여기 하나로 둔다. 다시 갈리는 것은 [BundleWriterTest] 가 소스를 훑어 막는다.
 *
 * ## 구동 식별자는 한 벌에 하나다
 *
 * ★★앞 판의 창구는 내보낼 때마다 새로 찍었다. 30분 돌린 한 판에서 식별자가 `-10930` 까지 갔다(실측).
 * 읽는 쪽은 「같은 구동의 같은 줄에는 한 번만 부른다」로 승인을 거르는데, 식별자가 매번 바뀌면
 * **그 거름망이 통째로 죽는다** — 같은 한 벌을 다시 훑을 때마다 승인을 또 시도한다. 승인은 제안을
 * 소모하므로 두 번째는 거절로 오고, 그러면 대장에 **일어나지 않은 거절**이 쌓인다.
 *
 * 그래서 식별자는 **만들 때 한 번** 정해진다. 자리(`dir`)도 만들 때 정해지므로 **한 쓰개가 두 벌을
 * 쓸 길이 없다** — 한 벌마다 하나를 만드는 것이 유일한 쓰는 법이다.
 *
 * ## 안 늘면 안 쓴다
 *
 * 두 대장은 append-only 라 **줄 수가 곧 변경 신호**다. 안 늘었는데 다시 쓰면 읽는 쪽이 세 파일을
 * 한 벌로 집을 때 파일마다 다른 순간을 볼 수 있다. 쓰는 순서는 대장 먼저, 안내가 마지막이다 —
 * 안내가 나타나는 것이 «다 나왔다» 는 신호이고, 그때 그 수만큼의 줄이 이미 자리에 있다.
 *
 * ## 한 벌은 한 인스턴스의 것이다
 *
 * 안내에 [instanceId] 를 싣는다(내보내기 판 8, §15.322). 인시던트 id 와 실행 id 는 인스턴스 안의 셈이라 재기동 뒤 같은 문자열이 다시
 * 나올 수 있다 — 한 사본을 가리키는 것은 (인스턴스, 인시던트 id)이고, 재기동을 넘어 같은 사건을 잇는 것은 줄의 `incidentKey` 다.
 *
 * @param instanceId 이 벌을 내는 미들웨어 인스턴스([dev.picasso.middleware.Middleware.instanceId]).
 */
class BundleWriter(
    private val dir: Path,
    private val instanceId: String,
    private val runId: String = LedgerExport.newRunId(Instant.now()),
) {

    /** 마지막으로 쓴 (사건 수, 탐색 수). 안 썼으면 널이다 — **0,0 과 «아직 안 썼다» 는 다르다.** */
    private var written: Pair<Int, Int>? = null

    /** 썼으면 true, 대장이 안 늘어 건너뛰었으면 false. */
    fun snapshot(
        incidents: List<IncidentBundle>,
        searches: List<RemedySearchRecord>,
        virtualNow: Instant,
    ): Boolean {
        val size = incidents.size to searches.size
        if (size == written) return false

        Files.createDirectories(dir)
        atomically(LedgerExport.INCIDENTS, LedgerExport.incidents(incidents))
        atomically(LedgerExport.REMEDY_SEARCHES, LedgerExport.remedySearches(searches))
        val wall = Instant.now()
        atomically(
            LedgerExport.MANIFEST,
            LedgerExport.manifest(runId, wall, virtualNow, incidents.size, searches.size, instanceId),
        )
        written = size
        return true
    }

    /** 마지막으로 쓴 결과 통보 수. 안 썼으면 널이다. */
    private var carried: Int? = null

    /**
     * 결과 통보를 **같은 자리에 따로** 놓는다(ADR 48). 대장 한 벌의 안내는 이 파일을 세지 않는다 — 판도 주인도
     * 다르다. 줄마다 제 판과 인스턴스를 들므로 안내 없이 읽힌다.
     *
     * **담는 쪽의 참조일 뿐 실물 담는 쪽이 아니다.** 상류 확인(`ack`)을 하지 않으므로 같은 통보가 매번 다시 실리고,
     * 읽는 쪽은 그것을 «같은 줄이 다시 왔다» 로 받아야 한다 — 실물 담는 쪽도 최소 한 번 전달이다.
     *
     * @return 썼으면 true, 통보가 안 늘어 건너뛰었으면 false.
     */
    fun carry(responses: List<JobResponse>, instanceId: String): Boolean {
        if (responses.size == carried) return false
        Files.createDirectories(dir)
        atomically(ResultExport.JOB_RESPONSES, ResultExport.jobResponses(responses, instanceId))
        carried = responses.size
        return true
    }

    /**
     * 임시 이름으로 쓰고 이름만 바꾼다. **반쯤 쓰인 파일을 집어 가면 그것은 한 벌이 아니라
     * 파편이고, 파편과 빈 한 벌은 같은 모양이다.**
     */
    private fun atomically(name: String, body: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, body)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
