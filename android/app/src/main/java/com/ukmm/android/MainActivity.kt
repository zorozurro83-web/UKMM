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
import java.util.zip.ZipInputStream

object Native {
    init { System.loadLibrary("uk_android") }
    @JvmStatic external fun version(): String
}

data class Entry(val uri: Uri, val name: String, val isDir: Boolean)

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var entries: List<Entry> = emptyList()
    private val reqFolder = 1001
    private val reqMod = 1002
    private val suffix = ".disabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.textSize = 20f
        title.text = try { Native.version() } catch (e: Throwable) { "Gagal memuat library: $e" }
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
            showFolder(uri)
        } else if (requestCode == reqMod) {
            val tree = savedTree() ?: return
            status.text = "Mengimpor mod..."
            Thread {
                try {
                    val n = importBnp(uri, tree)
                    runOnUiThread {
                        status.text = "Selesai: $n file diekstrak"
                        showFolder(tree)
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Gagal impor: $e" }
                }
            }.start()
        }
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
                if (!ok) status.text = "Gagal menghapus"
                showFolder(tree)
                if (ok) status.text = "Terhapus: " + e.name.removeSuffix(suffix)
            }
        }.start()
    }

    // ---------- Impor .bnp ----------

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

    // .bnp adalah file zip. Fungsi ini mengekstraknya ke subfolder di folder mod.
    private fun importBnp(fileUri: Uri, treeUri: Uri): Int {
        val displayName = queryName(fileUri) ?: "mod"
        val modName = displayName.substringBeforeLast('.')
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
        var count = 0

        val input = contentResolver.openInputStream(fileUri)
            ?: throw Exception("Tidak bisa membuka file mod")
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/').trim('/')
                val safe = path.isNotEmpty() && !path.split('/').any { it == ".." }
                if (safe) {
                    if (entry.isDirectory) {
                        ensureDir(cache, path)
                    } else {
                        val idx = path.lastIndexOf('/')
                        val dirPath = if (idx < 0) "" else path.substring(0, idx)
                        val fileName = path.substring(idx + 1)
                        val dir = ensureDir(cache, dirPath)
                        if (dir != null) {
                            val f = DocumentsContract.createDocument(
                                contentResolver,
                                dir,
                                "application/octet-stream",
                                fileName
                            )
                            if (f != null) {
                                contentResolver.openOutputStream(f)?.use { out -> zis.copyTo(out) }
                                count++
                            }
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        return count
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
