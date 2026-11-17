package dev.picasso.adapter.host

import dev.picasso.contracts.wire.FenceDurability
import dev.picasso.contracts.wire.FenceStore
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.name

/**
 * 제어자 세대 바닥값의 파일 저장(ADR 54) — **기체마다 파일 하나, 숫자 하나.** `adapter-host` 가 재시작을 넘어 바닥값을 들게 한다.
 *
 * - **적용 전 기록이다.** [save] 는 임시 파일에 쓰고 디스크에 내린 뒤 이름 바꾸기로 갈아 끼운다. 대조는 이것이 돌아온 뒤에 메모리 바닥값을
 *   올린다 — 죽어도 파일이 메모리보다 낮지 않다.
 * - **손상이면 서지 않는다.** [load] 가 숫자가 아닌 파일·음수를 만나면 던지고, 대조가 서지 않아 호스트가 뜨지 않는다. 바닥값을 모른 채
 *   받으면 옛 세대가 통과한다. 고치는 것은 운영자다(파일을 지우면 그 기체의 바닥값이 0 이 된다 — 수동 복구, 한계 대장 «§15.330 · 세대 오염»).
 * - **배타다.** 디렉터리의 잠금 파일을 프로세스가 사는 동안 쥔다. 같은 디렉터리를 두 호스트가 쓰면 둘째가 뜨지 않는다. 다른 디렉터리를
 *   쓰는 두 호스트가 같은 기체를 맡는 것은 막지 못한다(한계 대장 «§15.330 · 수신자 둘»).
 *
 * 이름은 `robot_id` 를 URL 인코딩한 것 + `.floor` 다. 남은 `.tmp` 는 읽지 않는다.
 */
class FileFenceStore(private val dir: Path) : FenceStore, AutoCloseable {

    override val durability: FenceDurability = FenceDurability.DURABLE

    private val channel: FileChannel
    private val lock: FileLock

    init {
        Files.createDirectories(dir)
        channel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try {
            channel.tryLock()
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            null
        } ?: run {
            channel.close()
            throw IllegalStateException("바닥값 디렉터리를 다른 호스트가 쓰고 있다: $dir")
        }
    }

    override fun load(): Map<String, Long> = Files.list(dir).use { files ->
        files.filter { it.name.endsWith(SUFFIX) }.toList().associate { file ->
            val robot = URLDecoder.decode(file.name.removeSuffix(SUFFIX), Charsets.UTF_8)
            val text = Files.readString(file).trim()
            val floor = text.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalStateException("바닥값 파일이 손상됐다: $file ('$text') — 운영자가 확인한다")
            robot to floor
        }
    }

    override fun save(robotId: String, floor: Long) {
        val name = URLEncoder.encode(robotId, Charsets.UTF_8)
        val target = dir.resolve(name + SUFFIX)
        val tmp = dir.resolve("$name$SUFFIX.tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            out.write(java.nio.ByteBuffer.wrap("$floor\n".toByteArray(Charsets.UTF_8)))
            out.force(true)
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // 이름 바꾸기를 디스크에 내린다(디렉터리 동기화). 디렉터리를 채널로 못 여는 파일 시스템(윈도)에서는 못 한다 — 그때 전원이 나가면
        // 이름 바꾸기 전의 값으로 돌아갈 수 있다. 바닥값이 낮아지는 쪽이라 `DURABLE` 의 단서다(한계 대장 «§15.330 · 휘발 바닥값»).
        runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
    }

    override fun close() {
        lock.release()
        channel.close()
    }

    private companion object {
        const val SUFFIX = ".floor"
        const val LOCK = ".lock"
    }
}
