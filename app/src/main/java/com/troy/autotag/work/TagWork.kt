package com.troy.autotag.work

import android.content.Context
import android.media.MediaScannerConnection
import android.os.PowerManager
import com.troy.autotag.Prefs
import com.troy.autotag.enrich.EnrichEngine
import com.troy.autotag.model.Track
import java.io.File

/**
 * 실행 중인(또는 마지막으로 끝난) 태그 작업의 상태를 들고 있는 곳.
 *
 * 예전에는 MainActivity 안의 스레드가 작업을 돌렸는데, S22+ 같은 최신
 * One UI에서는 화면을 끄거나 앱을 내리면 삼성 배터리 관리가 그 스레드를
 * 죽여서 작업이 중간에 끊겼다(쓰다 만 파일이 남을 수도 있다).
 * 이제 실제 작업은 포그라운드 서비스(TagService)가 돌리고, 이 객체는
 * 서비스와 화면 사이의 다리 역할만 한다: 로그/진행을 버퍼에 쌓아 두면
 * 화면이 다시 붙을 때 그대로 다시 보여 준다.
 */
object TagWork {

    interface Listener {
        fun onLog(line: String)
        fun onProgress(done: Int, total: Int, current: String)
        fun onFinished()
    }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var cancelRequested = false

    /** 화면에 붙어 있는 리스너(없으면 로그는 버퍼에만 쌓인다). */
    @Volatile
    var listener: Listener? = null

    private val lock = Any()
    private val logBuffer = ArrayDeque<String>()
    private const val MAX_LOG_LINES = 500

    var totalCount: Int = 0
        private set
    var doneCount: Int = 0
        private set
    var currentName: String = ""
        private set
    var summary: String? = null
        private set

    private var pendingTracks: List<Track> = emptyList()

    /** 작업 중인 목록(화면이 재생성돼도 목록/상태를 다시 보여 주기 위함). */
    @Volatile
    var lastTracks: List<Track> = emptyList()
        private set

    /** 서비스를 통해 작업을 시작한다. 이미 돌고 있으면 false. */
    fun start(context: Context, selected: List<Track>): Boolean {
        if (running || selected.isEmpty()) return false
        running = true // 워커가 뜨기 전 연속 탭으로 두 번 시작되는 것 방지
        synchronized(lock) {
            pendingTracks = selected
            lastTracks = selected
            cancelRequested = false
            summary = null
            doneCount = 0
            totalCount = selected.size
            currentName = ""
        }
        return TagService.start(context)
    }

    fun requestStop() {
        cancelRequested = true
        emitLog("중지 요청… (진행 중인 파일이 끝나면 멈춥니다)")
    }

    fun isCancelRequested(): Boolean = cancelRequested

    fun logSnapshot(): List<String> = synchronized(lock) { ArrayList(logBuffer) }

    fun emitLog(line: String) {
        synchronized(lock) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        listener?.onLog(line)
    }

    /**
     * 서비스의 워커 스레드에서 호출된다. 엔진 실행 + SAF 되쓰기 +
     * 웨이크락 유지 + 미디어 스캔까지 한 번에 처리하고 끝까지 블로킹한다.
     */
    fun execute(context: Context, progressHook: (Int, Int, String) -> Unit) {
        val tracks: List<Track>
        synchronized(lock) {
            tracks = pendingTracks
            pendingTracks = emptyList()
        }
        if (tracks.isEmpty()) {
            running = false
            return
        }
        running = true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoTag:tagging",
        )?.apply { setReferenceCounted(false) }
        try {
            // 화면이 꺼져도 CPU가 잠들지 않게 (최대 4시간 안전장치)
            runCatching { wakeLock?.acquire(4 * 60 * 60 * 1000L) }

            emitLog("=== 시작: ${tracks.size}개 파일 ===")
            val prefs = Prefs(context)
            val engine = EnrichEngine(
                prefs = prefs,
                log = { line -> emitLog(line) },
                cancelled = { cancelRequested },
            )
            engine.run(tracks) { done, total, name ->
                doneCount = done
                totalCount = total
                currentName = name
                progressHook(done, total, name)
                listener?.onProgress(done, total, name)
            }

            // SAF로 가져온 파일은 캐시에서 고친 뒤 원본에 되쓴다.
            for (track in tracks) {
                if (cancelRequested) break
                if (track.needsCopyBack && track.uri != null) {
                    val ok = copyBack(context, track)
                    emitLog(
                        if (ok) "  ↳ 원본 파일에 반영: ${track.file.name}"
                        else "  ↳ 원본 반영 실패(수정본은 앱 캐시에 남아 있음): ${track.file.name}"
                    )
                }
            }

            rescanChangedFiles(context, tracks)

            val okCount = tracks.count { it.changes.isNotEmpty() }
            val failCount = tracks.count {
                it.error != null || it.status.contains("실패")
            }
            summary = if (cancelRequested) {
                "중지됨: 변경 ${okCount}개 / 전체 ${tracks.size}개"
            } else {
                "완료: 변경 ${okCount}개 / 실패 ${failCount}개 / 전체 ${tracks.size}개"
            }
            emitLog("=== $summary ===")
        } catch (t: Throwable) {
            summary = "오류로 중단: ${t.message ?: t.javaClass.simpleName}"
            emitLog("오류: ${t.message}")
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            running = false
            listener?.onFinished()
        }
    }

    /**
     * 바뀐 파일을 미디어 스캐너에 다시 알려 준다. 안 그러면 삼성 뮤직 등
     * 플레이어가 예전 제목/커버(미디어 DB 캐시)를 한참 동안 계속 보여 준다.
     */
    private fun rescanChangedFiles(context: Context, tracks: List<Track>) {
        val paths = ArrayList<String>()
        for (track in tracks) {
            if (track.needsCopyBack || track.changes.isEmpty()) continue
            paths.add(track.file.absolutePath)
            val lrc = File(track.file.parentFile, track.file.nameWithoutExtension + ".lrc")
            if (lrc.exists()) paths.add(lrc.absolutePath)
        }
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> }
        }
    }

    /** 되쓴 뒤 크기가 맞는지까지 확인한다(짧게 쓰인 파일을 성공으로 오인 방지). */
    private fun copyBack(context: Context, track: Track): Boolean {
        val uri = track.uri ?: return false
        return try {
            val written = context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                track.file.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            written == track.file.length()
        } catch (t: Throwable) {
            false
        }
    }
}
