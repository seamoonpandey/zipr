@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package app.zipr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One file found under the chosen folder. [path] is relative to that folder and becomes the zip entry name. */
class Entry(val docId: String, val path: String, val size: Long, val kind: Int) {
    var checked = true
}

private const val KIND_OTHER = 0
private const val KIND_PHOTO = 1
private const val KIND_VIDEO = 2
private const val REQ_TREE = 1
private const val REQ_CREATE = 2
private const val UI_THROTTLE_MS = 100

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var emptyView: View
    private lateinit var listView: View
    private lateinit var workView: View
    private lateinit var folderNameView: TextView
    private lateinit var filters: RadioGroup
    private lateinit var selectAll: CheckBox
    private lateinit var summary: TextView
    private lateinit var zipButton: Button
    private lateinit var workTitle: TextView
    private lateinit var workDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private val adapter = FileAdapter()

    private var treeUri: Uri? = null
    private var folderName = ""
    private var all: List<Entry> = emptyList()
    private var visible: List<Entry> = emptyList()
    private var filter = KIND_OTHER // KIND_OTHER here means "All"
    private var scanning = false
    private var zipping = false

    // Bumped to invalidate a running scan (new folder picked / activity finishing).
    @Volatile private var scanGen = 0
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+ is always edge-to-edge: keep content clear of the system bars.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
            insets
        }

        emptyView = findViewById(R.id.emptyView)
        listView = findViewById(R.id.listView)
        workView = findViewById(R.id.workView)
        folderNameView = findViewById(R.id.folderName)
        filters = findViewById(R.id.filters)
        selectAll = findViewById(R.id.selectAll)
        summary = findViewById(R.id.summary)
        zipButton = findViewById(R.id.zipButton)
        workTitle = findViewById(R.id.workTitle)
        workDetail = findViewById(R.id.workDetail)
        progress = findViewById(R.id.progress)
        primaryButton = findViewById(R.id.primaryButton)
        secondaryButton = findViewById(R.id.secondaryButton)

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.chooseButton).setOnClickListener { chooseFolder() }
        filters.setOnCheckedChangeListener { _, id ->
            filter = when (id) {
                R.id.filterPhotos -> KIND_PHOTO
                R.id.filterVideos -> KIND_VIDEO
                else -> KIND_OTHER
            }
            applyFilter()
        }
        selectAll.setOnClickListener {
            val c = selectAll.isChecked
            visible.forEach { it.checked = c }
            adapter.notifyDataSetChanged()
            updateSummary()
        }
        zipButton.setOnClickListener { createZipDocument() }

        if (Build.VERSION.SDK_INT >= 33) {
            // targetSdk 36+ no longer calls onBackPressed.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
        show(emptyView)
    }

    @SuppressLint("GestureBackNavigation") // API < 33 path; 33+ uses onBackInvokedDispatcher above.
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun handleBack(): Boolean = when {
        zipping -> { moveTaskToBack(true); true }
        listView.visibility == View.VISIBLE -> { reset(); true }
        else -> false
    }

    override fun onDestroy() {
        if (isFinishing) {
            scanGen++
            cancelled = true
            worker.shutdown()
        }
        super.onDestroy()
    }

    private fun show(v: View) {
        emptyView.visibility = if (v === emptyView) View.VISIBLE else View.GONE
        listView.visibility = if (v === listView) View.VISIBLE else View.GONE
        workView.visibility = if (v === workView) View.VISIBLE else View.GONE
    }

    private fun reset() {
        scanGen++
        scanning = false
        treeUri = null
        all = emptyList()
        visible = emptyList()
        adapter.notifyDataSetChanged()
        show(emptyView)
    }

    private fun launch(intent: Intent, req: Int) {
        try {
            startActivityForResult(intent, req)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseFolder() = launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)

    private fun createZipDocument() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "$folderName-$stamp.zip"),
            REQ_CREATE,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_TREE -> openFolder(uri)
            REQ_CREATE -> startZip(uri)
        }
    }

    // ---- Folder scan ----

    private fun openFolder(tree: Uri) {
        treeUri = tree
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        folderName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId))
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "zipr" }
        folderNameView.text = folderName
        all = emptyList()
        filters.check(R.id.filterAll)
        scanning = true
        applyFilter()
        show(listView)
        scan(tree, rootId)
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun scan(tree: Uri, rootId: String) {
        val gen = ++scanGen
        summary.text = getString(R.string.scanning, num(0))
        worker.execute {
            val found = ArrayList<Entry>()
            val cols = arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
            )
            // Iterative DFS: (documentId, relative path prefix).
            val stack = ArrayDeque<Pair<String, String>>()
            stack.add(rootId to "")
            var lastPost = 0L
            while (stack.isNotEmpty() && gen == scanGen) {
                val (dir, prefix) = stack.removeLast()
                try {
                    contentResolver.query(
                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir), cols, null, null, null,
                    )?.use { c ->
                        while (c.moveToNext() && gen == scanGen) {
                            val id = c.getString(0)
                            val name = c.getString(1) ?: id
                            val mime = c.getString(2) ?: ""
                            if (mime == Document.MIME_TYPE_DIR) {
                                stack.add(id to "$prefix$name/")
                                continue
                            }
                            // Virtual docs (e.g. Google Docs) have no bytes to read.
                            if (c.getInt(4) and Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                            val kind = when {
                                mime.startsWith("image/") -> KIND_PHOTO
                                mime.startsWith("video/") -> KIND_VIDEO
                                else -> KIND_OTHER
                            }
                            found.add(Entry(id, prefix + name, if (c.isNull(3)) 0 else c.getLong(3), kind))
                            val now = SystemClock.uptimeMillis()
                            if (now - lastPost >= UI_THROTTLE_MS) {
                                lastPost = now
                                val n = found.size
                                runOnUiThread { if (gen == scanGen) summary.text = getString(R.string.scanning, num(n)) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Unreadable subfolder: skip it, keep the rest.
                }
            }
            found.sortBy { it.path }
            runOnUiThread {
                if (gen == scanGen) {
                    all = found
                    scanning = false
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        visible = if (filter == KIND_OTHER) all else all.filter { it.kind == filter }
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        var count = 0
        var bytes = 0L
        for (e in visible) if (e.checked) { count++; bytes += e.size }
        selectAll.isChecked = visible.isNotEmpty() && count == visible.size
        selectAll.isEnabled = !scanning
        if (!scanning) summary.text = getString(R.string.summary, num(count), size(bytes))
        zipButton.text = getString(R.string.zip_n, num(count))
        zipButton.isEnabled = !scanning && count > 0
    }

    // ---- Zipping ----

    private fun startZip(out: Uri) {
        val tree = treeUri ?: return
        val items = visible.filter { it.checked }
        val totalBytes = items.sumOf { it.size }
        cancelled = false
        zipping = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(workView)
        workTitle.setText(R.string.zipping)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        workDetail.text = getString(R.string.progress, num(0), num(items.size), size(0), size(totalBytes))
        primaryButton.setText(R.string.cancel)
        primaryButton.setOnClickListener { cancelled = true; primaryButton.isEnabled = false }
        primaryButton.isEnabled = true
        secondaryButton.visibility = View.GONE

        // shortcut: no foreground service, zipping stops if the OS kills the app in background; upgrade to a foreground service if users report it
        worker.execute {
            var error: String? = null
            var skipped = 0
            var doneBytes = 0L
            var zippedBytes = 0L
            var lastPost = 0L
            val handle = try {
                NativeZip.open(openOutput(out).detachFd())
            } catch (e: Exception) {
                error = e.toString()
                0L
            }
            if (handle == 0L) {
                if (error == null) error = "cannot open output"
            } else {
                // One file open at a time so thousands of files never exhaust the fd limit.
                for ((i, e) in items.withIndex()) {
                    if (cancelled) break
                    val err = try {
                        val pfd = contentResolver.openFileDescriptor(
                            DocumentsContract.buildDocumentUriUsingTree(tree, e.docId), "r",
                        )
                        if (pfd == null) "cannot open" else NativeZip.add(handle, pfd.detachFd(), e.path)
                    } catch (ex: Exception) {
                        ex.toString()
                    }
                    if (err != null) skipped++ else zippedBytes += e.size
                    doneBytes += e.size
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPost >= UI_THROTTLE_MS) {
                        lastPost = now
                        val n = i + 1
                        val b = doneBytes
                        runOnUiThread {
                            progress.progress = if (totalBytes > 0) (b * 1000 / totalBytes).toInt() else 0
                            workDetail.text = getString(R.string.progress, num(n), num(items.size), size(b), size(totalBytes))
                        }
                    }
                }
                if (cancelled) NativeZip.abort(handle) else error = NativeZip.finish(handle)
            }
            if (cancelled || error != null) {
                try { DocumentsContract.deleteDocument(contentResolver, out) } catch (e: Exception) { }
            }
            val zipped = items.size - skipped
            runOnUiThread {
                zipping = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when {
                    cancelled -> show(listView)
                    error != null -> {
                        Toast.makeText(this, getString(R.string.error, error), Toast.LENGTH_LONG).show()
                        show(listView)
                    }
                    else -> showDone(out, zipped, zippedBytes, skipped)
                }
            }
        }
    }

    private fun openOutput(uri: Uri) = try {
        contentResolver.openFileDescriptor(uri, "rwt")
    } catch (e: Exception) {
        null
    } ?: contentResolver.openFileDescriptor(uri, "w") ?: error("cannot open output")

    private fun showDone(zip: Uri, files: Int, bytes: Long, skipped: Int) {
        workTitle.text = getString(R.string.done, num(files), size(bytes))
        progress.visibility = View.GONE
        workDetail.text = if (skipped > 0) getString(R.string.skipped, num(skipped)) else ""
        primaryButton.isEnabled = true
        primaryButton.setText(R.string.share)
        primaryButton.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, zip)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.setText(R.string.zip_another)
        secondaryButton.setOnClickListener { reset(); chooseFolder() }
    }

    private fun num(n: Int): String = NumberFormat.getIntegerInstance().format(n)
    private fun size(b: Long): String = Formatter.formatShortFileSize(this, b)

    // ---- List ----

    private inner class FileAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = visible.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(layoutInflater.inflate(R.layout.row, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = visible[position]
            h.check.isChecked = e.checked
            h.name.text = e.path
            h.size.text = size(e.size)
        }
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.name)
        val size: TextView = v.findViewById(R.id.size)

        init {
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                visible[p].checked = !visible[p].checked
                adapter.notifyItemChanged(p)
                updateSummary()
            }
        }
    }
}
