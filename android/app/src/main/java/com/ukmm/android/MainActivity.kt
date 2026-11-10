package com.ukmm.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

object Native {
    init { System.loadLibrary("uk_android") }
    @JvmStatic external fun version(): String
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView
    private val reqFolder = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.textSize = 20f
        title.text = try { Native.version() } catch (e: Throwable) { "Gagal memuat library: $e" }
        root.addView(title)

        val btn = Button(this)
        btn.text = "Pilih folder mod"
        btn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(intent, reqFolder)
        }
        root.addView(btn)

        status = TextView(this)
        status.textSize = 16f
        status.text = "Belum ada folder dipilih"
        root.addView(status)

        list = ListView(this)
        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        val saved = getSharedPreferences("ukmm", MODE_PRIVATE).getString("mod_folder", null)
        if (saved != null) showFolder(Uri.parse(saved))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqFolder || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // izin permanen gagal, folder tetap bisa dibaca sesi ini
        }
        getSharedPreferences("ukmm", MODE_PRIVATE).edit()
            .putString("mod_folder", uri.toString())
            .apply()
        showFolder(uri)
    }

    private fun showFolder(uri: Uri) {
        val names = ArrayList<String>()
        try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val isDir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                    names.add(if (isDir) "[Folder] $name" else name)
                }
            }
        } catch (e: Exception) {
            status.text = "Gagal membaca folder: $e"
            return
        }
        names.sort()
        status.text = "Isi folder: ${names.size} item"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }
}
