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
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
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
import com.troy.autotag.model.Track
import com.troy.autotag.tag.TagIO
import com.troy.autotag.work.TagWork
import java.io.File

/**
 * 단순화된 한 화면 UI.
 *
 * 흐름은 딱 세 단계다:
 *  1) [음악 불러오기] 한 번으로 기기 전체/폴더/파일 중 골라 담는다.
 *  2) 고를 파일이 있으면 목록을 **길게 눌러** 선택 모드에 들어가고,
 *     그 뒤로는 톡톡 눌러 선택/해제한다. 아무것도 선택하지 않으면 전체가 대상.
 *  3) 아래 큰 [태그 채우기] 버튼. 작업 중에는 같은 버튼이 [중지]가 된다.
 *
 * 실제 작업은 포그라운드 서비스(TagService + TagWork)가 돌리므로
 * 화면을 끄거나 앱을 내려도 계속 진행된다.
 */
class MainActivity : Activity(), TagWork.Listener {

    private lateinit var prefs: Prefs
    private val tracks = ArrayList<Track>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var statusText: TextView
    private lateinit var listHeaderText: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var currentText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnLogToggle: Button

    /** 길게 눌러 들어가는 선택 모드인지 (이 모드에서만 탭이 선택을 바꾼다). */
    private var selectionMode = false
    private var importCounter = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        TagIO.init()

        statusText = findViewById(R.id.statusText)
        listHeaderText = findViewById(R.id.listHeaderText)
        selectionBar = findViewById(R.id.selectionBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        listView = findViewById(R.id.fileList)
        progressBar = findViewById(R.id.progressBar)
        currentText = findViewById(R.id.currentText)
        btnStart = findViewById(R.id.btnStart)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnLogToggle = findViewById(R.id.btnLogToggle)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (!selectionMode) {
                // 선택 모드가 아니면 탭은 선택을 바꾸지 않는다.
                listView.setItemChecked(position, false)
            }
            updateSelectionUi()
        }
        listView.setOnItemLongClickListener { _, view, position, _ ->
            if (!TagWork.running) {
                selectionMode = true
                listView.setItemChecked(position, true)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                updateSelectionUi()
            }
            true
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearTracks() }
        findViewById<Button>(R.id.btnSelAll).setOnClickListener {
            for (i in tracks.indices) listView.setItemChecked(i, true)
            updateSelectionUi()
        }
        findViewById<Button>(R.id.btnSelNone).setOnClickListener {
            clearChecks()
            updateSelectionUi()
        }
        findViewById<Button>(R.id.btnSelDone).setOnClickListener {
            selectionMode = false
            updateSelectionUi()
        }
        btnLogToggle.setOnClickListener {
            val show = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (show) View.VISIBLE else View.GONE
            btnCopyLog.visibility = if (show) View.VISIBLE else View.GONE
            btnLogToggle.text = if (show) "로그 숨기기" else "로그 보기"
        }
        btnCopyLog.setOnClickListener { copyLog() }
        statusText.setOnClickListener {
            if (!hasAllFilesAccess()) requestAllFilesAccess()
        }
        btnStart.setOnClickListener {
            if (TagWork.running) {
                TagWork.requestStop()
                renderRunningState()
            } else {
                startProcessing()
            }
        }

        // 작업 도중 화면이 재생성된 경우 진행 중인 목록을 복원한다.
        if (tracks.isEmpty() && TagWork.lastTracks.isNotEmpty()) {
            tracks.addAll(TagWork.lastTracks)
            renderList()
        }
        restoreLog()
        updatePermissionStatus()
        renderRunningState()
        updateSelectionUi()
    }

    override fun onStart() {
        super.onStart()
        TagWork.listener = this
        restoreLog()
        renderRunningState()
    }

    override fun onStop() {
        if (TagWork.listener === this) TagWork.listener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    // ------------------------------------------------------------- TagWork.Listener

    override fun onLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    override fun onProgress(done: Int, total: Int, current: String) {
        runOnUiThread {
            progressBar.progress = if (total > 0) done * 100 / total else 0
            currentText.text = "($done/$total) $current"
            if (done % 3 == 0 || done == total) renderList()
        }
    }

    override fun onFinished() {
        runOnUiThread {
            renderList()
            renderRunningState()
            TagWork.summary?.let { toast(it) }
        }
    }

    // --------------------------------------------------------------- permissions

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasReadAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionStatus() {
        statusText.text = when {
            hasAllFilesAccess() ->
                "파일 접근 OK · ${if (prefs.useAcoustId) "AcoustID ON" else "AcoustID OFF"}"
            hasReadAudioPermission() ->
                "음악 읽기는 가능 · 파일을 고치려면 여기를 눌러 '모든 파일 액세스'를 허용하세요"
            else ->
                "⚠ 권한 필요 — 여기를 눌러 허용하세요"
        }
        statusText.setTextColor(
            Color.parseColor(if (hasAllFilesAccess()) "#1B5E20" else "#B71C1C")
        )
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
                REQ_LEGACY_STORAGE,
            )
        }
    }

    private fun requestReadAudio() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissions(arrayOf(permission), REQ_READ_AUDIO)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
        if (requestCode == REQ_READ_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            scanMediaStore()
        }
    }

    /** 목록을 읽을 권한이 없으면 어떤 권한을 받을지 한 번만 물어본다. */
    private fun ensureLibraryAccess(onReady: () -> Unit) {
        if (hasAllFilesAccess() || hasReadAudioPermission()) {
            onReady()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("권한이 필요합니다")
            .setMessage("음악 목록을 불러오려면 접근 권한이 필요합니다.\n파일을 직접 고쳐 쓰려면 '모든 파일 액세스'가 필요합니다.")
            .setPositiveButton("모든 파일 허용") { _, _ -> requestAllFilesAccess() }
            .setNeutralButton("음악만 허용") { _, _ -> requestReadAudio() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ------------------------------------------------------------------ 불러오기

    private fun showAddDialog() {
        if (TagWork.running) {
            toast("작업 중에는 추가할 수 없습니다")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("음악 불러오기")
            .setItems(arrayOf("기기 음악 전체", "폴더 선택", "파일 선택")) { _, which ->
                when (which) {
                    0 -> ensureLibraryAccess { scanMediaStore() }
                    1 -> {
                        if (hasAllFilesAccess()) {
                            showFolderDialog(Environment.getExternalStorageDirectory() ?: File("/storage"))
                        } else {
                            toast("폴더를 직접 읽으려면 '모든 파일 액세스'가 필요합니다")
                            requestAllFilesAccess()
                        }
                    }
                    else -> pickFiles()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun scanMediaStore() {
        toast("음악을 찾는 중…")
        Thread {
            val found = ArrayList<File>()
            runCatching {
                val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME)
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
            }.onFailure {
                runOnUiThread { appendLog("음악 스캔 실패: ${it.message}") }
            }
            runOnUiThread { addFiles(found, "음악 전체") }
        }.start()
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
        toast("폴더를 찾는 중…")
        Thread {
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
            runOnUiThread { addFiles(found, dir.name) }
        }.start()
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
        // 파일 복사가 UI 스레드를 막지 않게 백그라운드에서 처리한다.
        Thread {
            val added = ArrayList<Track>()
            val notes = ArrayList<String>()
            for (uri in uris) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val direct = resolveFile(uri)
                if (direct != null && direct.canWrite()) {
                    added.add(Track(file = direct, uri = uri))
                } else {
                    val cached = importToCache(uri)
                    if (cached != null) {
                        added.add(Track(file = cached, uri = uri, needsCopyBack = true))
                        notes.add("캐시로 복사: ${cached.name} (작업 후 원본에 되씁니다)")
                    } else {
                        notes.add("파일을 열 수 없음: $uri")
                    }
                }
            }
            runOnUiThread {
                notes.forEach { appendLog(it) }
                val before = tracks.size
                val existingPaths = tracks.map { it.file.absolutePath }.toHashSet()
                for (track in added) {
                    if (!existingPaths.contains(track.file.absolutePath)) tracks.add(track)
                }
                appendLog("파일 선택: ${tracks.size - before}개 파일 추가 (총 ${tracks.size}개)")
                renderList()
            }
        }.start()
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

    private fun addFiles(found: List<File>, source: String) {
        val existingPaths = tracks.map { it.file.absolutePath }.toHashSet()
        var added = 0
        for (file in found) {
            if (existingPaths.contains(file.absolutePath)) continue
            if (!file.canRead()) continue
            tracks.add(Track(file = file))
            added++
        }
        appendLog("$source: ${added}개 파일 추가 (총 ${tracks.size}개)")
        renderList()
    }

    // ------------------------------------------------------------------ 목록/선택

    private fun isAudio(file: File): Boolean =
        Prefs.AUDIO_EXTENSIONS.contains(file.extension.lowercase())

    private fun clearTracks() {
        if (TagWork.running) {
            toast("작업 중에는 지울 수 없습니다")
            return
        }
        listView.clearChoices()
        tracks.clear()
        selectionMode = false
        renderList()
    }

    private fun renderList() {
        labels.clear()
        for (track in tracks) {
            val existing = track.tags
            val detail = when {
                track.status.isNotBlank() -> track.status
                existing.hasAnyTag -> existing.summary()
                else -> "(태그 없음)"
            }
            labels.add("${track.file.name}\n   $detail")
        }
        adapter.notifyDataSetChanged()
        updateSelectionUi()
        updatePermissionStatus()
    }

    private fun clearChecks() {
        for (i in tracks.indices) listView.setItemChecked(i, false)
    }

    private fun checkedTracks(): List<Track> {
        val out = ArrayList<Track>()
        for (i in tracks.indices) {
            if (listView.isItemChecked(i)) out.add(tracks[i])
        }
        return out
    }

    /** 실행 대상: 선택한 게 있으면 그것만, 없으면 전체. */
    private fun tracksToRun(): List<Track> {
        val checked = checkedTracks()
        return if (checked.isNotEmpty()) checked else ArrayList(tracks)
    }

    private fun updateSelectionUi() {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        val checked = checkedTracks().size
        listHeaderText.text = when {
            tracks.isEmpty() -> "파일 없음 — '음악 불러오기'로 시작하세요"
            selectionMode -> "${checked}개 선택됨 · 눌러서 선택/해제"
            checked > 0 -> "파일 ${tracks.size}개 · ${checked}개 선택됨 (길게 누르면 더 선택)"
            else -> "파일 ${tracks.size}개 · 길게 누르면 선택, 선택 없으면 전체 처리"
        }
        if (!TagWork.running) {
            btnStart.text = when {
                tracks.isEmpty() -> "태그 채우기"
                checked > 0 -> "선택한 ${checked}개 태그 채우기"
                else -> "전체 ${tracks.size}개 태그 채우기"
            }
            btnStart.isEnabled = tracks.isNotEmpty()
        }
    }

    private fun renderRunningState() {
        val running = TagWork.running
        progressBar.visibility = if (running) View.VISIBLE else View.GONE
        currentText.visibility = if (running) View.VISIBLE else View.GONE
        btnStart.text = if (running) "중지" else btnStart.text
        btnStart.isEnabled = running || tracks.isNotEmpty()
        if (running) {
            val total = TagWork.totalCount
            val done = TagWork.doneCount
            if (total > 0) {
                progressBar.progress = done * 100 / total
                currentText.text = "($done/$total) ${TagWork.currentName}"
            }
        } else {
            updateSelectionUi()
        }
    }

    // -------------------------------------------------------------------- 실행

    private fun startProcessing() {
        val selected = tracksToRun()
        if (selected.isEmpty()) {
            toast("먼저 음악을 불러오세요")
            return
        }
        // 직접 경로로 쓰는 파일이 있으면 모든 파일 액세스가 필요하다.
        if (selected.any { !it.needsCopyBack } && !hasAllFilesAccess()) {
            AlertDialog.Builder(this)
                .setTitle("모든 파일 액세스가 필요합니다")
                .setMessage("선택한 파일을 직접 고쳐 쓰려면 '모든 파일 액세스' 허용이 필요합니다. (파일 선택으로 가져온 파일은 없어도 됩니다)")
                .setPositiveButton("허용하러 가기") { _, _ -> requestAllFilesAccess() }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        // 삼성 One UI는 배터리 최적화 대상 앱의 백그라운드 작업을 멈출 수 있다.
        if (!prefs.batteryHintSeen && !isBatteryOptimizationIgnored()) {
            prefs.batteryHintSeen = true
            AlertDialog.Builder(this)
                .setTitle("배터리 설정 안내")
                .setMessage("화면을 끈 채로 오래 돌리면 삼성 배터리 관리가 작업을 멈출 수 있습니다. 배터리 최적화에서 제외하면 끝까지 안정적으로 돕니다.")
                .setPositiveButton("그냥 시작") { _, _ -> reallyStart(selected) }
                .setNeutralButton("배터리 설정 열기") { _, _ -> openBatteryOptimizationSettings() }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        reallyStart(selected)
    }

    private fun reallyStart(selected: List<Track>) {
        requestNotificationPermission()
        if (TagWork.start(this, selected)) {
            renderRunningState()
        } else {
            toast("이미 작업 중입니다")
        }
    }

    // -------------------------------------------------------------------- 로그 등

    private fun restoreLog() {
        val snapshot = TagWork.logSnapshot()
        logText.text = if (snapshot.isEmpty()) {
            "AutoTag 준비 완료"
        } else {
            snapshot.joinToString("\n")
        }
    }

    private fun appendLog(line: String) {
        val current = logText.text.toString()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        val trimmed = if (merged.length > 60000) merged.substring(merged.length - 60000) else merged
        logText.text = trimmed
        if (logScroll.visibility == View.VISIBLE) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AutoTag log", logText.text))
        toast("로그를 복사했습니다")
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

        val savers = ArrayList<() -> Unit>()

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
            savers.add { set(box.isChecked) }
        }

        fun addNumber(label: String, value: String, inputType: Int, set: (String) -> Unit) {
            val title = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, 12, 0, 0)
            }
            val edit = EditText(this).apply {
                setText(value)
                this.inputType = inputType
                textSize = 13f
            }
            container.addView(title)
            container.addView(edit)
            savers.add { set(edit.text.toString()) }
        }

        fun addTitle(text: String, topPadding: Int = 0) {
            container.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                setPadding(0, topPadding, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }

        addTitle("검색 소스")
        addCheck("AcoustID 지문 사용 (우선)", { prefs.useAcoustId }) { prefs.useAcoustId = it }
        addCheck("벅스(Bugs) 검색 (국내 음원 우선)", { prefs.useBugs }) { prefs.useBugs = it }
        addCheck("MusicBrainz 검색", { prefs.useMusicBrainz }) { prefs.useMusicBrainz = it }
        addCheck("iTunes 검색 (커버/트랙번호)", { prefs.useItunes }) { prefs.useItunes = it }
        addCheck("Deezer 검색 (보조)", { prefs.useDeezer }) { prefs.useDeezer = it }
        addCheck("LRCLIB 가사(싱크 우선)", { prefs.useLyrics }) { prefs.useLyrics = it }

        addTitle("태그 정책", topPadding = 16)
        addCheck("빈 항목만 채우기 (기존 값 유지)", { prefs.fillMissingOnly }) { prefs.fillMissingOnly = it }
        addCheck("가수/앨범 표기 통일", { prefs.unifyArtistAlbum }) { prefs.unifyArtistAlbum = it }
        addCheck("가수에서 feat. 제거", { prefs.stripFeaturing }) { prefs.stripFeaturing = it }
        addCheck("앨범가수(AlbumArtist) 기록", { prefs.writeAlbumArtist }) { prefs.writeAlbumArtist = it }
        addCheck("싱크가사 우선", { prefs.preferSyncedLyrics }) { prefs.preferSyncedLyrics = it }
        addCheck(".lrc 파일도 생성", { prefs.writeLrcSidecar }) { prefs.writeLrcSidecar = it }
        addCheck("커버 이미지 삽입", { prefs.embedArtwork }) { prefs.embedArtwork = it }
        addCheck("더 고화질 커버가 있으면 교체", { prefs.upgradeArtwork }) { prefs.upgradeArtwork = it }
        addCheck("원본 .bak 백업", { prefs.backupOriginal }) { prefs.backupOriginal = it }
        addCheck("MusicBrainz ID 기록", { prefs.writeMbIds }) { prefs.writeMbIds = it }

        addTitle("세부 설정", topPadding = 16)
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
                savers.forEach { it() }
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

    companion object {
        private const val REQUEST_PICK_FILES = 2001
        private const val REQ_LEGACY_STORAGE = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val REQ_READ_AUDIO = 1003
    }
}
