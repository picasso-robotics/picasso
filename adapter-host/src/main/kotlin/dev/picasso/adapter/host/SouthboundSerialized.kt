package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import java.time.Instant

/**
 * 어댑터의 모든 호출을 링크 잠금 아래로 보내는 껍질(§15.268).
 *
 * 펌프의 읽기는 호스트 잠금 밖에서 돌고 명령은 호스트 잠금 아래에서 돈다. 둘이 같은 어댑터를 동시에 부르면 어댑터 안의
 * 상태(도는 태스크 등)가 두 스레드에서 바뀌고, 플릿 뒤 기체들의 공유 링크가 동시에 불린다(§15.106). 그래서 이 껍질이
 * 호출마다 [lock] 을 쥔다. 잠금 순서는 언제나 호스트 다음 링크다 — 읽기 쪽은 링크만 쥐므로 서로 기다리다 멎지 않는다.
 *
 * **기본 구현이 있는 멤버도 전부 넘긴다.** 넘기지 않으면 이 껍질의 기본 구현이 돌아 감싼 어댑터의 구현을 건너뛴다.
 *
 * @param lock 쥘 잠금. 부를 때마다 읽는다 — [AdapterHost] 가 기체들에 공유 잠금을 나중에 건다.
 */
internal class SouthboundSerialized(private val delegate: RobotAdapter, private val lock: () -> Any) : RobotAdapter {

    private inline fun <T> link(block: () -> T): T = synchronized(lock()) { block() }

    override val state: TaskState get() = link { delegate.state }

    override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance =
        link { delegate.accept(taskId, skillType, parameters, startedAt) }

    override fun poll(now: Instant): TaskState = link { delegate.poll(now) }

    override fun pause(): Applied = link { delegate.pause() }

    override fun resume(parameters: Map<String, Any>): Applied = link { delegate.resume(parameters) }

    override fun cancel(): Applied = link { delegate.cancel() }

    override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied =
        link { delegate.update(taskId, skillType, parameters, at) }

    override fun retry(parameters: Map<String, Any>): Applied = link { delegate.retry(parameters) }

    override fun hold(): HoldObservation = link { delegate.hold() }

    override fun progress(): ProgressObservation = link { delegate.progress() }

    override fun faults(): FaultObservation = link { delegate.faults() }

    override fun failure(): Fault? = link { delegate.failure() }

    override fun result(): String? = link { delegate.result() }

    override fun knownSiteNames(): SiteNames = link { delegate.knownSiteNames() }

    override fun robotSoftware(): String? = link { delegate.robotSoftware() }

    override val pollObservesVendor: Boolean get() = link { delegate.pollObservesVendor }
}
