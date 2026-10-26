package com.troy.autotag

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.troy.autotag.enrich.EnrichEngine
import com.troy.autotag.fp.Chromaprint
import com.troy.autotag.model.Track
import com.troy.autotag.tag.TagIO
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val tracks = ArrayList<Track>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var statusText: TextView
    private lateinit var selectionText: TextView
    private lateinit var currentText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    @Volatile
    private var running = false
    private var importCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        TagIO.init()

        statusText = findViewById(R.id.statusText)
        selectionText = findViewById(R.id.selectionText)
        currentText = findViewById(R.id.currentText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        listView = findViewById(R.id.fileList)
        progressBar = findViewById(R.id.progressBar)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, _, _ -> updateSelectionText() }

        findViewById<Button>(R.id.btnPermission).setOnClickListener { requestAllFilesAccess() }
        findViewById<Button>(R.id.btnScanMusic).setOnClickListener { scanMediaStore() }
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.btnPickFiles).setOnClickListener { pickFiles() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearTracks() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        btnStart.setOnClickListener { startProcessing() }
        btnStop.setOnClickListener { stopProcessing() }

        appendLog("AutoTag 준비 완료. AcoustID 키: ${prefs.acoustIdKey.take(4)}…")
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    // ------------------------------------------------------------ permissions

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updatePermissionStatus() {
        val granted = hasAllFilesAccess()
        statusText.text = if (granted) {
            "권한 OK · AcoustID ${if (prefs.useAcoustId) "ON" else "OFF"} · 파일 ${tracks.size}개"
        } else {
            "⚠ 파일 접근 권한 필요 ('권한' 버튼을 누르세요)"
        }
        statusText.setTextColor(if (granted) Color.parseColor("#1B5E20") else Color.parseColor("#B71C1C"))
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                toast("이미 모든 파일 접근 권한이 있습니다")
                return
            }
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                1001,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    // ---------------------------------------------------------------- scanning

    private fun clearTracks() {
        if (running) {
            toast("작업 중에는 지울 수 없습니다")
            return
        }
        tracks.clear()
        renderList()
    }

    private fun scanMediaStore() {
        if (!hasAllFilesAccess()) {
            toast("먼저 '권한'을 허용해 주세요")
            return
        }
        val found = ArrayList<File>()
        val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME)
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + "!=0",
                null,
                null,
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext() && found.size < 5000) {
                    val path = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                    if (path.isNullOrBlank()) continue
                    val file = File(path)
                    if (file.exists() && isAudio(file)) found.add(file)
                }
            }
        }.onFailure { appendLog("MediaStore 스캔 실패: ${it.message}") }
        addFiles(found, "음악 전체")
    }

    private fun pickFolder() {
        if (!hasAllFilesAccess()) {
            toast("먼저 '권한'을 허용해 주세요")
            return
        }
        val root = Environment.getExternalStorageDirectory() ?: File("/storage")
        showFolderDialog(root)
    }

    private fun showFolderDialog(start: File) {
        fun open(dir: File) {
            val children = (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
                .sortedBy { it.name.lowercase() }
            val names = ArrayList<String>()
            names.add("✓ 이 폴더의 모든 음악 (${dir.name})")
            dir.parentFile?.let { names.add(".. 상위 폴더") }
            children.forEach { names.add("[${it.name}]") }
            AlertDialog.Builder(this)
                .setTitle(dir.absolutePath)
                .setItems(names.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> scanFolder(dir)
                        which == 1 && dir.parentFile != null -> open(dir.parentFile!!)
                        else -> {
                            val index = which - (if (dir.parentFile != null) 2 else 1)
                            children.getOrNull(index)?.let { open(it) }
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
        open(start)
    }

    private fun scanFolder(dir: File) {
        val found = ArrayList<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(dir to 0)
        while (stack.isNotEmpty() && found.size < 5000) {
            val (current, depth) = stack.removeLast()
            val entries = current.listFiles() ?: continue
            for (entry in entries) {
                if (found.size >= 5000) break
                if (entry.isDirectory) {
                    if (depth < 12 && !entry.name.startsWith(".") &&
                        entry.name != "Android" && entry.canRead()
                    ) {
                        stack.add(entry to depth + 1)
                    }
                } else if (isAudio(entry)) {
                    found.add(entry)
                }
            }
        }
        addFiles(found, dir.name)
    }

    private fun isAudio(file: File): Boolean =
        Prefs.AUDIO_EXTENSIONS.contains(file.extension.lowercase())

    private fun addFiles(found: List<File>, source: String) {
        val existingPaths = tracks.map { it.file.absolutePath }.toHashSet()
        var added = 0
        for (file in found) {
            if (existingPaths.contains(file.absolutePath)) continue
            if (!file.canRead()) {
                if (file.extension.lowercase() in setOf("mp3", "flac", "m4a", "ogg", "wav", "aiff", "aif")) {
                    appendLog("읽기 불가: ${file.absolutePath}")
                }
                continue
            }
            tracks.add(Track(file = file))
            added++
        }
        appendLog("$source: ${added}개 파일 추가 (총 ${tracks.size}개)")
        renderList()
        checkSelectAll()
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_FILES) }
            .onFailure { toast("파일 선택기를 열 수 없습니다") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        for (uri in uris) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val direct = resolveFile(uri)
            if (direct != null && direct.canWrite()) {
                tracks.add(Track(file = direct, uri = uri))
            } else {
                val cached = importToCache(uri)
                if (cached != null) {
                    tracks.add(Track(file = cached, uri = uri, needsCopyBack = true))
                    appendLog("캐시로 복사: ${cached.name} (작업 후 원본에 되씁니다)")
                } else {
                    appendLog("파일을 열 수 없음: $uri")
                }
            }
        }
        renderList()
        checkSelectAll()
    }

    private fun resolveFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index >= 0) {
                        val path = cursor.getString(index)
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.exists()) return file
                        }
                    }
                }
            }
        }
        return null
    }

    private fun importToCache(uri: Uri): File? {
        return runCatching {
            val dir = File(cacheDir, "import").apply { mkdirs() }
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "track"
            if (!name.contains('.')) name = "$name.mp3"
            val target = File(dir, "${importCounter++}_$name")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

    // -------------------------------------------------------------------- list

    private fun renderList() {
        labels.clear()
        for (track in tracks) {
            val existing = track.tags
            val detail = when {
                !track.status.isBlank() -> track.status
                existing.hasAnyTag -> existing.summary()
                else -> "(태그 없음)"
            }
            labels.add("${track.file.name}\n   $detail")
        }
        adapter.notifyDataSetChanged()
        updateSelectionText()
        updatePermissionStatus()
    }

    private fun toggleSelectAll() {
        val allChecked = (0 until listView.count).all { listView.isItemChecked(it) }
        for (i in 0 until listView.count) listView.setItemChecked(i, !allChecked)
        updateSelectionText()
    }

    private fun checkSelectAll() {
        for (i in 0 until listView.count) listView.setItemChecked(i, true)
        updateSelectionText()
    }

    private fun selectedTracks(): List<Track> {
        val out = ArrayList<Track>()
        for (i in 0 until listView.count) {
            if (listView.isItemChecked(i) && i < tracks.size) out.add(tracks[i])
        }
        return out
    }

    private fun updateSelectionText() {
        val selected = selectedTracks().size
        selectionText.text = "파일 ${tracks.size}개 · 선택 ${selected}개"
    }

    // ----------------------------------------------------------------- process

    private fun startProcessing() {
        if (running) return
        val selected = selectedTracks()
        if (selected.isEmpty()) {
            toast("처리할 파일을 선택하세요")
            return
        }
        // SAF 로 가져온 파일은 캐시에서 고친 뒤 원본 URI 로 되쓰기 때문에 전체 접근 권한이 없어도 된다.
        val needsDirectAccess = selected.any { !it.needsCopyBack }
        if (needsDirectAccess && !hasAllFilesAccess()) {
            toast("파일 쓰기 권한이 필요합니다 ('권한' 버튼)")
            return
        }
        running = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        progressBar.progress = 0
        appendLog("=== 시작: ${selected.size}개 파일 ===")

        val engine = EnrichEngine(
            prefs = prefs,
            log = { line -> runOnUiThread { appendLog(line) } },
            cancelled = { !running },
        )
        Thread {
            try {
                engine.run(selected) { done, total, name ->
                    runOnUiThread {
                        progressBar.progress = if (total > 0) done * 100 / total else 0
                        currentText.text = "($done/$total) $name"
                        if (done % 3 == 0 || done == total) renderList()
                    }
                }
                for (track in selected) {
                    if (track.needsCopyBack && track.uri != null) {
                        val ok = copyBack(track)
                        runOnUiThread {
                            appendLog(
                                if (ok) "  ↳ 원본 파일에 반영: ${track.file.name}"
                                else "  ↳ 원본 반영 실패: ${track.file.name}"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { appendLog("오류: ${t.message}") }
            } finally {
                runOnUiThread {
                    running = false
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    renderList()
                    val okCount = selected.count { it.status.contains("변경") }
                    val failCount = selected.count { it.error != null || it.status.contains("실패") }
                    appendLog("=== 완료: 변경 ${okCount}개 / 실패 ${failCount}개 / 전체 ${selected.size}개 ===")
                    currentText.text = "완료"
                }
            }
        }.start()
    }

    private fun stopProcessing() {
        running = false
        appendLog("중지 요청… (진행 중인 파일이 끝나면 멈춥니다)")
    }

    private fun copyBack(track: Track): Boolean {
        val uri = track.uri ?: return false
        return runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                track.file.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
    }

    private fun copyLog() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AutoTag log", logText.text))
        toast("로그를 복사했습니다")
    }

    private fun appendLog(line: String) {
        val current = logText.text.toString()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        val trimmed = if (merged.length > 60000) merged.substring(merged.length - 60000) else merged
        logText.text = trimmed
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------------- settings

    private fun showSettings() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        scroll.addView(container)

        val checkboxes = ArrayList<Pair<CheckBox, () -> Unit>>()

        fun addCheck(
            label: String,
            get: () -> Boolean,
            set: (Boolean) -> Unit,
        ) {
            val box = CheckBox(this).apply {
                text = label
                isChecked = get()
                textSize = 13f
            }
            container.addView(box)
            checkboxes.add(box to { set(box.isChecked) })
        }

        fun addNumber(label: String, value: String, inputType: Int, set: (String) -> Unit) {
            val title = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, 12, 0, 0)
            }
            val edit = EditText(this).apply {
                text = value
                this.inputType = inputType
                textSize = 13f
            }
            container.addView(title)
            container.addView(edit)
            checkboxes.add(CheckBox(this).apply { visibility = View.GONE } to { set(edit.text.toString()) })
        }

        val title = TextView(this).apply {
            text = "소스"
            textSize = 14f
            this.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(title)

        addCheck("AcoustID 지문 사용 (우선)", { prefs.useAcoustId }) { prefs.useAcoustId = it }
        addCheck("MusicBrainz 검색", { prefs.useMusicBrainz }) { prefs.useMusicBrainz = it }
        addCheck("iTunes 검색 (커버/트랙번호)", { prefs.useItunes }) { prefs.useItunes = it }
        addCheck("Deezer 검색 (보조)", { prefs.useDeezer }) { prefs.useDeezer = it }
        addCheck("LRCLIB 가사(싱크 우선)", { prefs.useLyrics }) { prefs.useLyrics = it }

        val title2 = TextView(this).apply {
            text = "태그 정책"
            textSize = 14f
            setPadding(0, 16, 0, 0)
            this.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(title2)
        addCheck("빈 항목만 채우기 (기존 값 유지)", { prefs.fillMissingOnly }) { prefs.fillMissingOnly = it }
        addCheck("가수/앨범 표기 통일", { prefs.unifyArtistAlbum }) { prefs.unifyArtistAlbum = it }
        addCheck("가수에서 feat. 제거", { prefs.stripFeaturing }) { prefs.stripFeaturing = it }
        addCheck("앨범가수(AlbumArtist) 기록", { prefs.writeAlbumArtist }) { prefs.writeAlbumArtist = it }
        addCheck("싱크가사 우선", { prefs.preferSyncedLyrics }) { prefs.preferSyncedLyrics = it }
        addCheck(".lrc 파일도 생성", { prefs.writeLrcSidecar }) { prefs.writeLrcSidecar = it }
        addCheck("커버 이미지 삽입", { prefs.embedArtwork }) { prefs.embedArtwork = it }
        addCheck("원본 .bak 백업", { prefs.backupOriginal }) { prefs.backupOriginal = it }
        addCheck("MusicBrainz ID 기록", { prefs.writeMbIds }) { prefs.writeMbIds = it }

        val title3 = TextView(this).apply {
            text = "세부 설정"
            textSize = 14f
            setPadding(0, 16, 0, 0)
            this.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(title3)
        addNumber("AcoustID API 키", prefs.acoustIdKey, InputType.TYPE_CLASS_TEXT) {
            if (it.isNotBlank()) prefs.acoustIdKey = it
        }
        addNumber("iTunes 국가 (KR/US/JP)", prefs.itunesCountry, InputType.TYPE_CLASS_TEXT) {
            if (it.isNotBlank()) prefs.itunesCountry = it
        }
        addNumber(
            "AcoustID 최소 점수 (%)",
            (prefs.acoustIdThreshold * 100).toInt().toString(),
            InputType.TYPE_CLASS_NUMBER,
        ) {
            val value = it.toIntOrNull() ?: return@addNumber
            prefs.acoustIdThreshold = (value.coerceIn(50, 99)) / 100f
        }
        addNumber(
            "커버 최대 크기(px, 0=원본)",
            prefs.artworkMaxSize.toString(),
            InputType.TYPE_CLASS_NUMBER,
        ) {
            prefs.artworkMaxSize = it.toIntOrNull()?.coerceIn(0, 2000) ?: 1000
        }

        AlertDialog.Builder(this)
            .setTitle("설정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                checkboxes.forEach { it.second() }
                appendLog("설정 저장됨")
                updatePermissionStatus()
            }
            .setNeutralButton("별칭 초기화") { _, _ ->
                prefs.artistAliases = mutableMapOf()
                prefs.albumAliases = mutableMapOf()
                appendLog("학습된 별칭을 초기화했습니다")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_FILES = 2001
    }
}
