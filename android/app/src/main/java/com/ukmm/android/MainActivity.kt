package com.ukmm.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile

object Native {
    var loadError: String? = null
    init {
        try { System.loadLibrary("uk_android") } catch (e: Throwable) { loadError = e.toString() }
    }
    @JvmStatic external fun version(): String
    ...
      @JvmStatic external fun init(dataDir: String) 
}  

data class Entry(val uri: Uri, val name: String, val isDir: Boolean)

data class ImportResult(val written: Int, val total: Int, val failed: List<String>)

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var entries: List<Entry> = emptyList()
    private val reqFolder = 1001
    private val reqMod = 1002
    private val suffix = ".disabled"

    // Ubah ke true setelah fungsi installBnp di Rust sudah selesai.
    // false = ekstrak mentah seperti sebelumnya.
    private val useNative = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
                if (Native.loadError == null) try { Native.init(File(filesDir, "ukmm_data").absolutePath) } catch (e: Throwable) {}
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.textSize = 20f
                title.text = Native.loadError?.let { "Gagal memuat library: $it" }
            ?: try { Native.version() } catch (e: Throwable) { "Gagal: $e" }
        root.addView(title)

        val btnFolder = Button(this)
        btnFolder.text = "Pilih folder mod"
        btnFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(intent, reqFolder)
        }
        root.addView(btnFolder)

        val btnImport = Button(this)
        btnImport.text = "Impor mod (.bnp)"
        btnImport.setOnClickListener {
            if (savedTree() == null) {
                status.text = "Pilih folder mod dulu"
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(intent, reqMod)
            }
        }
        root.addView(btnImport)

        status = TextView(this)
        status.textSize = 16f
        status.text = "Belum ada folder dipilih"
        root.addView(status)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in entries.indices) showActions(entries[pos])
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        val tree = savedTree()
        if (tree != null) showFolder(tree)
    }

    private fun savedTree(): Uri? {
        val s = getSharedPreferences("ukmm", MODE_PRIVATE).getString("mod_folder", null)
        return if (s == null) null else Uri.parse(s)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == reqFolder) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // izin permanen gagal, folder tetap bisa dipakai sesi ini
            }
            getSharedPreferences("ukmm", MODE_PRIVATE).edit()
                .putString("mod_folder", uri.toString())
                .apply()
            status.text = ""
            showFolder(uri)
        } else if (requestCode == reqMod) {
            val tree = savedTree() ?: return
            status.text = "Mengimpor mod..."
            Thread {
                try {
                    val r = if (useNative) importBnp(uri, tree) else importBnpRaw(uri, tree)
                    runOnUiThread {
                        showFolder(tree)
                        status.text = "Selesai: ${r.written} dari ${r.total} file diproses, ${r.failed.size} gagal"
                        if (r.failed.isNotEmpty()) showFailures(r)
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        status.text = "Gagal impor: $e"
                        showFolder(tree)
                    }
                }
            }.start()
        }
    }

    private fun showFailures(r: ImportResult) {
        val shown = r.failed.take(25)
        val more = r.failed.size - shown.size
        val text = shown.joinToString("\n") + if (more > 0) "\n... dan $more lainnya" else ""
        AlertDialog.Builder(this)
            .setTitle("${r.failed.size} file gagal")
            .setMessage(text)
            .setPositiveButton("Tutup", null)
            .show()
    }

    // ---------- Aktif/nonaktif dan hapus ----------

    private fun showActions(e: Entry) {
        val disabled = e.name.endsWith(suffix)
        val options = if (e.isDir) {
            arrayOf(if (disabled) "Aktifkan" else "Nonaktifkan", "Hapus")
        } else {
            arrayOf("Hapus")
        }
        AlertDialog.Builder(this)
            .setTitle(e.name.removeSuffix(suffix))
            .setItems(options) { _, which ->
                if (options[which] == "Hapus") confirmDelete(e) else toggle(e)
            }
            .show()
    }

    private fun toggle(e: Entry) {
        val tree = savedTree() ?: return
        val newName = if (e.name.endsWith(suffix)) e.name.removeSuffix(suffix) else e.name + suffix
        try {
            val result = DocumentsContract.renameDocument(contentResolver, e.uri, newName)
            if (result == null) status.text = "Gagal mengganti status mod"
        } catch (ex: Exception) {
            status.text = "Gagal mengganti status mod: $ex"
        }
        showFolder(tree)
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Hapus " + e.name.removeSuffix(suffix) + "?")
            .setMessage("Isinya akan dihapus permanen dan tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> doDelete(e) }
            .show()
    }

    private fun doDelete(e: Entry) {
        val tree = savedTree() ?: return
        status.text = "Menghapus..."
        Thread {
            val ok = try {
                DocumentsContract.deleteDocument(contentResolver, e.uri)
            } catch (ex: Exception) {
                false
            }
            runOnUiThread {
                showFolder(tree)
                status.text = if (ok) "Terhapus: " + e.name.removeSuffix(suffix) else "Gagal menghapus"
            }
        }.start()
    }

    // ---------- Impor .bnp: alur baru lewat Rust ----------

    private fun copyToPrivate(uri: Uri): File {
        val dir = File(filesDir, "work").apply { mkdirs() }
        val dst = File(dir, "in.bnp")
        contentResolver.openInputStream(uri)!!.use { i ->
            dst.outputStream().use { o -> i.copyTo(o) }
        }
        return dst
    }

    private fun copyTreeToSaf(src: File, parent: Uri, failed: ArrayList<String>) {
        for (f in src.listFiles() ?: return) {
            if (f.isDirectory) {
                val d = DocumentsContract.createDocument(
                    contentResolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, f.name
                )
                if (d == null) { failed.add("${f.name}: gagal buat folder"); continue }
                copyTreeToSaf(f, d, failed)
            } else {
                val d = DocumentsContract.createDocument(
                    contentResolver, parent, "application/octet-stream", f.name
                )
                val out = d?.let { contentResolver.openOutputStream(it) }
                if (out == null) { failed.add("${f.name}: gagal buat file"); continue }
                try {
                    out.use { o -> f.inputStream().use { it.copyTo(o) } }
                } catch (e: Exception) {
                    failed.add("${f.name}: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // Rust harus menulis hasil akhir ke <dataDir>/output/
    private fun importBnp(fileUri: Uri, treeUri: Uri): ImportResult {
        val displayName = queryName(fileUri) ?: "mod"
        val modName = displayName.substringBeforeLast('.')
        val work = File(filesDir, "work")
        val dataDir = File(filesDir, "ukmm_data")
        val out = File(dataDir, "output")
        try {
            work.deleteRecursively()
            out.deleteRecursively()
            dataDir.mkdirs()

            val bnp = copyToPrivate(fileUri)
            val res = Native.installBnp(bnp.absolutePath, dataDir.absolutePath)
            if (res != "OK") throw Exception(res)

            val total = out.walkTopDown().count { it.isFile }
            if (total == 0) throw Exception("Rust tidak menghasilkan file")

            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val modDir = DocumentsContract.createDocument(
                contentResolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, modName
            ) ?: throw Exception("Tidak bisa membuat folder $modName")

            val failed = ArrayList<String>()
            copyTreeToSaf(out, modDir, failed)

            val written = total - failed.size
            if (written <= 0) {
                try { DocumentsContract.deleteDocument(contentResolver, modDir) } catch (x: Exception) {}
                throw Exception("Tidak ada file yang berhasil disalin")
            }
            return ImportResult(written, total, failed)
        } finally {
            work.deleteRecursively()
            out.deleteRecursively()
        }
    }

    // ---------- Impor .bnp: ekstrak mentah (7z, dengan cadangan zip) ----------

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun ensureDir(cache: HashMap<String, Uri>, path: String): Uri? {
        val hit = cache[path]
        if (hit != null) return hit
        val idx = path.lastIndexOf('/')
        val parentPath = if (idx < 0) "" else path.substring(0, idx)
        val name = if (idx < 0) path else path.substring(idx + 1)
        val parent = ensureDir(cache, parentPath) ?: return null
        val created = DocumentsContract.createDocument(
            contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: return null
        cache[path] = created
        return created
    }

    // Menambah satu entri arsip ke folder mod. Mengembalikan true kalau sebuah file berhasil ditulis.
    // Kegagalan dicatat di daftar failed beserta alasannya.
    private fun addEntry(
        cache: HashMap<String, Uri>,
        rawName: String,
        isDir: Boolean,
        failed: ArrayList<String>,
        writer: (OutputStream) -> Unit
    ): Boolean {
        val path = rawName.replace('\\', '/').trim('/')
        if (path.isEmpty() || path.split('/').any { it == ".." }) {
            if (!isDir) failed.add("$rawName: nama tidak valid")
            return false
        }
        if (isDir) {
            ensureDir(cache, path)
            return false
        }
        val idx = path.lastIndexOf('/')
        val dirPath = if (idx < 0) "" else path.substring(0, idx)
        val fileName = path.substring(idx + 1)
        val dir = ensureDir(cache, dirPath)
        if (dir == null) {
            failed.add("$path: gagal membuat folder")
            return false
        }
        val f = DocumentsContract.createDocument(
            contentResolver,
            dir,
            "application/octet-stream",
            fileName
        )
        if (f == null) {
            failed.add("$path: gagal membuat file")
            return false
        }
        val out = contentResolver.openOutputStream(f)
        if (out == null) {
            failed.add("$path: gagal membuka file tujuan")
            return false
        }
        return try {
            out.use { writer(it) }
            true
        } catch (ex: Exception) {
            failed.add("$path: gagal menulis (${ex.javaClass.simpleName})")
            false
        }
    }

    private fun importBnpRaw(fileUri: Uri, treeUri: Uri): ImportResult {
        val displayName = queryName(fileUri) ?: "mod"
        val modName = displayName.substringBeforeLast('.')

        // Salin dulu ke penyimpanan sementara supaya arsip bisa dibaca acak.
        val tmp = File(cacheDir, "import.tmp")
        try {
            val input = contentResolver.openInputStream(fileUri)
                ?: throw Exception("Tidak bisa membuka file mod")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }

            val head = ByteArray(6)
            val read = tmp.inputStream().use { it.read(head) }
            val is7z = read == 6 &&
                head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() &&
                head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() &&
                head[4] == 0x27.toByte() && head[5] == 0x1C.toByte()

            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            val modDir = DocumentsContract.createDocument(
                contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                modName
            ) ?: throw Exception("Tidak bisa membuat folder $modName")

            val cache = HashMap<String, Uri>()
            cache[""] = modDir
            val failed = ArrayList<String>()
            var count = 0
            var total = 0

            try {
                if (is7z) {
                    SevenZFile(tmp).use { sz ->
                        var e = sz.nextEntry
                        while (e != null) {
                            val entry = e
                            if (!entry.isDirectory) total++
                            val wrote = addEntry(cache, entry.name, entry.isDirectory, failed) { out ->
                                val buf = ByteArray(65536)
                                while (true) {
                                    val n = sz.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            if (wrote) count++
                            e = sz.nextEntry
                        }
                    }
                } else {
                    ZipInputStream(tmp.inputStream()).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            val entry = e
                            if (!entry.isDirectory) total++
                            val wrote = addEntry(cache, entry.name, entry.isDirectory, failed) { out ->
                                zis.copyTo(out)
                            }
                            if (wrote) count++
                            e = zis.nextEntry
                        }
                    }
                }
            } catch (ex: Throwable) {
                try { DocumentsContract.deleteDocument(contentResolver, modDir) } catch (x: Exception) {}
                throw ex
            }

            if (count == 0) {
                try { DocumentsContract.deleteDocument(contentResolver, modDir) } catch (x: Exception) {}
                val why = if (failed.isNotEmpty()) " (" + failed[0] + ")" else ""
                throw Exception("Tidak ada file yang berhasil diekstrak$why")
            }
            return ImportResult(count, total, failed)
        } finally {
            tmp.delete()
        }
    }

    // ---------- Daftar isi folder ----------

    private fun showFolder(uri: Uri) {
        val found = ArrayList<Entry>()
        try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1)
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                    found.add(Entry(docUri, name, isDir))
                }
            }
        } catch (e: Exception) {
            status.text = "Gagal membaca folder: $e"
            return
        }
        found.sortBy { it.name.lowercase() }
        entries = found

        val labels = found.map {
            if (!it.isDir) {
                it.name
            } else if (it.name.endsWith(suffix)) {
                "[NONAKTIF] " + it.name.removeSuffix(suffix)
            } else {
                "[AKTIF] " + it.name
            }
        }
        status.text = "Isi folder: ${found.size} item. Ketuk item untuk opsi."
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }
}
