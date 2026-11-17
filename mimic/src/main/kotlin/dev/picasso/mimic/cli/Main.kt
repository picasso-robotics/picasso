package dev.picasso.mimic.cli

import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.RealClock
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.profile.FileProfileSource
import dev.picasso.mimic.profile.ProfileRejected
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.report.SiteNameSummary
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.uplink.MqttPublisher
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.Topics
import dev.picasso.mimic.transport.RobotRegistry
import io.grpc.ServerBuilder
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

/**
 * `mimic --robot <id>=<profile.json> [--robot ...] --schema <path> [--port 0]`
 *
 * `--robot`을 **하나의 옵션**으로 둔다. `--profile`과 `--robot-id`를 각각
 * 반복시키면 짝이 어긋난 채로도 기동한다.
 */
class MimicCli {

    /**
     * @param instances 기체 id 별 인스턴스. 담는 쪽이 기체가 아는 사이트 명칭([RobotInstance.knownSiteNames])을 넣는
     *   자리다 — 현장의 명칭 티칭(`commissioning.md` Step 3)을 제어 서버 없이 흉내 낸다.
     */
    class Started(
        val server: MimicServer,
        val robotIds: Set<String>,
        private val instances: Map<String, RobotInstance> = emptyMap(),
        /** 이 서버의 발행을 감싼 레지스트리 연계. 닫을 때 적재 큐를 비운다. */
        private val link: RegistryLink = RegistryLink.none(),
    ) : AutoCloseable {
        /** 이 서버가 띄운 기체. 모르는 id 면 널이다. */
        fun instance(robotId: String): RobotInstance? = instances[robotId]

        /**
         * 서버를 내리고 적재 큐를 비운다([RegistryLink.close]). 서버만 내리면 큐에 남은 관측이 레지스트리에도 폴백에도
         * 안 간다(§15.210).
         */
        override fun close() {
            server.shutdown()
            link.close()
        }
    }

    /** 기동한 서버. 프로세스가 소유하며, 시험은 이것으로 닫는다. */
    var started: Started? = null
        private set

    /**
     * **`exitProcess`를 안에 두지 않는다.** 그러면 종료 코드를 시험할 때
     * 시험 JVM이 죽는다. `main`이 감싼다.
     */
    fun run(args: Array<String>, out: Appendable, err: Appendable): Int {
        val robots = mutableMapOf<String, Path>()
        var schema: Path? = null
        var port = 0
        var virtual = false
        var seed = 0L
        var registry: String? = null
        var token: String? = null
        var site = "default"
        var broker: String? = null
        var fallbackDir: Path? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val value = args.getOrNull(i + 1)
            if (arg.startsWith("--") && value == null) return usage(err, "$arg 에 값이 없다")
            when (arg) {
                "--robot" -> {
                    val spec = value!!
                    val parts = spec.split("=", limit = 2)
                    if (parts.size != 2 || parts.any { it.isBlank() }) {
                        return usage(err, "--robot 은 <id>=<profile.json> 형식이다: '$spec'")
                    }
                    // 조용히 덮으면 프로파일 하나가 사라진 채 기동한다.
                    if (robots.put(parts[0], Path.of(parts[1])) != null) {
                        return usage(err, "같은 robot_id를 두 번 줬다: ${parts[0]}")
                    }
                    i += 2
                }
                "--schema" -> { schema = Path.of(value!!); i += 2 }
                "--port" -> {
                    port = value!!.toIntOrNull() ?: return usage(err, "--port 가 정수가 아니다")
                    i += 2
                }
                "--clock" -> {
                    virtual = when (val mode = value!!) {
                        "virtual" -> true
                        "real" -> false
                        else -> return usage(err, "--clock 은 real 또는 virtual 이다: '$mode'")
                    }
                    i += 2
                }
                "--seed" -> {
                    seed = value!!.toLongOrNull() ?: return usage(err, "--seed 가 정수가 아니다")
                    i += 2
                }
                "--registry" -> { registry = value!!; i += 2 }
                "--ingest-token" -> { token = value!!; i += 2 }
                "--site" -> { site = value!!; i += 2 }
                "--broker" -> { broker = value!!; i += 2 }
                "--fallback-dir" -> { fallbackDir = Path.of(value!!); i += 2 }
                else -> return usage(err, "모르는 인자다: '$arg'")
            }
        }

        if (robots.isEmpty()) return usage(err, "--robot 이 하나도 없다")
        val schemaPath = schema ?: return usage(err, "--schema 가 없다")

        // **토큰 없이 레지스트리를 붙이지 않는다.** 적재 표면은 전부 401을
        // 낼 것이고(§15.38), 그러면 관측이 통째로 폴백 파일로 가면서
        // "붙었다"고 보고된다 — 기동에서 막는 편이 정직하다.
        if (registry != null && token.isNullOrBlank()) {
            return usage(err, "--registry 를 쓰면 --ingest-token 이 있어야 한다")
        }
        val link = registry?.let { RegistryLink.http(it, token!!, site, fallbackDir) }
            ?: RegistryLink.none()

        // **기동할 때 폴백을 먼저 민다**(§15.46). 프로세스가 다시 뜨는 것이
        // 가장 흔한 복구 계기다.
        link.replayFallbacks()?.let { outcome ->
            out.appendLine(
                "폴백 재적재: ${outcome.replayed}건 성공, ${outcome.failed.size}건 실패, " +
                    "${outcome.malformed}건 읽을 수 없음",
            )
        }

        val running = start(robots, schemaPath, port, virtual, seed, err, link, broker, site)
            ?: return 2
        started = running
        out.appendLine("mimic 이 포트 ${running.server.port} 에서 ${running.robotIds.sorted()} 를 호스팅한다")
        if (broker != null) out.appendLine("브로커 발행: $broker (site=$site)")
        if (registry != null) {
            out.appendLine("레지스트리 연계: $registry (site=$site)")
            link.fallbackPaths().forEach { out.appendLine("폴백 파일: $it") }
        }
        return 0
    }

    /**
     * §10.2 — 프로파일 로드 → 스키마 검증(실패 시 **기동 거부**) → 투영 →
     * 상태머신 → `session_id` → 포트 개방.
     *
     * **기체 하나가 거부되면 전부 기동하지 않는다.** 부분 기동은 소비자에게
     * 거짓말이다 — 없는 기체를 물으면 `NOT_FOUND`가 나오는데, 그것이
     * "설정에 없다"인지 "프로파일이 깨졌다"인지 구별할 방법이 없다.
     */
    fun start(
        robots: Map<String, Path>,
        schema: Path,
        port: Int,
        virtual: Boolean,
        seed: Long,
        err: Appendable,
        /** 레지스트리 연계. 붙이지 않으면 파일 모드로 돈다(§3.2의 "없을 때"). */
        link: RegistryLink = RegistryLink.none(),
        /**
         * MQTT 브로커. **없으면 아무 데도 안 나간다**(§15.30) — 기본이
         * in-process인 것은 §12.1의 결정성 때문이며, 시험 스위트는 이 값을
         * 주지 않는다.
         */
        broker: String? = null,
        site: String = "default",
        /**
         * 제어자 세대 대조(ADR 54). **기본은 대조하지 않는다** — 오늘 거동. 담는 쪽(현장·시험)이 대조를 켜고 저장을 고를 자리다 —
         * 이 자리가 없으면 CLI 로 띄운 미믹은 언제나 `UNFENCED` 다.
         */
        fence: dev.picasso.contracts.wire.ControllerFence = dev.picasso.contracts.wire.ControllerFence(),
    ): Started? {
        val source = FileProfileSource(schema)
        val clock: Clock = if (virtual) VirtualClock(Instant.EPOCH) else RealClock()

        // **기체가 보고하는 펌웨어를 발행 시점에 찾을 수 있게 해 둔다.**
        // link 는 인스턴스보다 먼저 만들어지므로 맵을 미리 두고 람다가
        // 그것을 본다 — 발행할 때는 이미 차 있다.
        val built = mutableMapOf<String, RobotInstance>()

        val instances = robots.map { (id, path) ->
            try {
                // **기체마다 하나다.** Last Will이 그 기체의 connection
                // 토픽이어야 하므로 발행자를 공유할 수 없다(§4.7).
                val outbound: Publisher = broker?.let {
                    MqttPublisher.connect(
                        it,
                        clientId = "picasso-mimic-$id",
                        willTopic = Topics.robot(
                            dev.picasso.contracts.wire.ContractIdentity.major,
                            site, id, Topics.Stream.connection,
                        ),
                        willHeader = dev.picasso.contracts.v1.MessageHeader.newBuilder()
                            .setRobotId(id).build(),
                    )
                } ?: Publisher.NONE
                RobotInstance(
                    id, source.load(path), clock, seed,
                    // 발행을 감싸 태스크 관측을 적재로 넘긴다. 연계가 없으면
                    // 그대로 지나간다.
                    // 사이트 이름 요약이 생존 보고에 함께 실린다(ADR 35) —
                    // 목록이 아니라 요약인 것이 요점이며, 레지스트리는 이름의
                    // 주인이 아니다.
                    publisher = link.wrap(
                        outbound,
                        software = { built[it]?.robotSoftware },
                        siteNames = { id ->
                            built[id]?.let { instance ->
                                val known = instance.knownSiteNames
                                SiteNameSummary(
                                    unsupported = known == null,
                                    count = known?.size ?: 0,
                                )
                            }
                        },
                    ),
                    site = site,
                ).also { built[id] = it }
            } catch (e: ProfileRejected) {
                err.appendLine("기동 거부: $id — ${e.message}")
                return null
            }
        }

        return Started(
            MimicServer(
                RobotRegistry(instances, fence = fence),
                ServerBuilder.forPort(port),
                link.reporter,
            ).start(),
            robots.keys,
            built.toMap(),
            link,
        )
    }

    private fun usage(err: Appendable, message: String): Int {
        err.appendLine(message)
        err.appendLine(
            "사용법: mimic --robot <id>=<profile.json> [--robot ...] --schema <path> " +
                "[--port <n>] [--clock real|virtual] [--seed <n>] " +
                "[--registry <url> --ingest-token <t> [--site <s>] [--fallback-dir <path>]] " +
                "[--broker <tcp://host:port>]",
        )
        return 2
    }
}

fun main(args: Array<String>) {
    val cli = MimicCli()
    val code = cli.run(args, System.out, System.err)
    if (code != 0) exitProcess(code)
    // 프로세스가 내려갈 때 적재 큐에 남은 관측을 비운다(§15.210).
    Runtime.getRuntime().addShutdownHook(Thread { cli.started?.close() })
    // 기동에 성공했으면 프로세스가 살아 있어야 한다.
    cli.started?.server?.awaitTermination()
}
