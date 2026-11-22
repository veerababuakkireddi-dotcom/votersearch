package com.sri2461.votersearch

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var db: VoterDb
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var progress: ProgressBar
    private val worker = Executors.newSingleThreadExecutor()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = VoterDb(this)
        makeUi()
        showState()
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 10)
        }
        root.addView(TextView(this).apply {
            text = "Voter Search"
            textSize = 25f
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "Import / Replace voter PDF"
            setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        })
        input = EditText(this).apply {
            hint = "Search English / Telugu name, voter ID or serial"
            setSingleLine(true)
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { search() }
        })
        status = TextView(this)
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress)

        val scroll = ScrollView(this)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showState() {
        val count = db.count()
        status.text = if (count > 0) {
            "$count voter-card entries saved on this phone. Search works offline."
        } else {
            "Select your voter PDF to import it. Internet is needed only for the first OCR-model download."
        }
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Import voter list?")
            .setMessage("The selected PDF will be copied into this app. Importing a new PDF replaces the currently saved voter list.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ -> importPdf(uri) }
            .show()
    }

    private fun importPdf(uri: Uri) {
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Copying PDF. Keep the app open…"

        worker.execute {
            var ocr: TessBaseAPI? = null
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var completed = false

            try {
                val pdf = File(filesDir, "voter_source.pdf")
                val source = contentResolver.openInputStream(uri)
                    ?: error("Unable to open the selected PDF")
                source.use { inputStream ->
                    FileOutputStream(pdf).use { outputStream -> inputStream.copyTo(outputStream) }
                }
                check(pdf.length() > 0L) { "The selected PDF is empty" }

                val tessRoot = File(filesDir, "tesseract")
                val tessData = File(tessRoot, "tessdata")
                check(tessData.mkdirs() || tessData.isDirectory) { "Cannot create OCR data folder" }

                updateStatus("Downloading / checking English OCR data…")
                downloadLanguageData(tessData, "eng")
                updateStatus("Downloading / checking Telugu OCR data…")
                downloadLanguageData(tessData, "tel")

                // tess-two expects the parent of the tessdata directory.
                updateStatus("Initializing English + Telugu OCR engine…")
                val engine = TessBaseAPI()
                ocr = engine
                // tessdata_fast files are LSTM-only, so the engine mode must be LSTM_ONLY.
                val initialized = engine.init(
                    tessRoot.absolutePath,
                    "eng+tel",
                    TessBaseAPI.OEM_LSTM_ONLY
                )
                if (!initialized) {
                    // Delete the language files so the next import downloads fresh copies.
                    File(tessData, "eng.traineddata").delete()
                    File(tessData, "tel.traineddata").delete()
                    error("Tesseract could not initialize eng+tel. The OCR data was reset; please try the import again.")
                }

                pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                check(totalPages > 2) { "This PDF has no voter pages after physical page 2" }

                // Clear only after the PDF and OCR engine have opened successfully.
                db.clear()
                var serial = 0
                val cardFolder = File(filesDir, "cards")
                check(cardFolder.mkdirs() || cardFolder.isDirectory) { "Cannot create card-image folder" }

                // Physical page 3 is PdfRenderer index 2 (zero-based).
                for (pageIndex in 2 until totalPages) {
                    val page = renderer.openPage(pageIndex)
                    var pageBitmap: Bitmap? = null
                    try {
                        // Render at 2x for legible Telugu text and voter-ID characters.
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        pageBitmap = bitmap
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        // The supplied B. Prathipadu PDF uses 3 columns x 9 rows.
                        val left = (bitmap.width * 0.095f).roundToInt()
                        val right = (bitmap.width * 0.91f).roundToInt()
                        val top = (bitmap.height * 0.115f).roundToInt()
                        val bottom = (bitmap.height * 0.915f).roundToInt()
                        val cellWidth = (right - left) / 3
                        val cellHeight = (bottom - top) / 9
                        check(cellWidth > 0 && cellHeight > 0) { "Invalid card grid on PDF page ${pageIndex + 1}" }

                        for (row in 0 until 9) {
                            for (column in 0 until 3) {
                                val x = left + column * cellWidth
                                val y = top + row * cellHeight
                                val rect = Rect(
                                    x.coerceAtLeast(0),
                                    y.coerceAtLeast(0),
                                    (x + cellWidth).coerceAtMost(bitmap.width),
                                    (y + cellHeight).coerceAtMost(bitmap.height)
                                )
                                if (rect.width() <= 0 || rect.height() <= 0) continue

                                val cardBitmap = Bitmap.createBitmap(
                                    bitmap,
                                    rect.left,
                                    rect.top,
                                    rect.width(),
                                    rect.height()
                                )
                                try {
                                    engine.setImage(cardBitmap)
                                    val recognizedText = engine.getUTF8Text().orEmpty()
                                        .replace(Regex("\\s+"), " ")
                                        .trim()

                                    serial++
                                    val imageFile = File(cardFolder, "card_$serial.jpg")
                                    FileOutputStream(imageFile).use { output ->
                                        check(cardBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                                            "Could not save card image $serial"
                                        }
                                    }
                                    db.insert(serial, pageIndex + 1, recognizedText, imageFile.absolutePath)
                                    engine.clear()
                                } finally {
                                    cardBitmap.recycle()
                                }
                            }
                        }
                    } finally {
                        pageBitmap?.recycle()
                        page.close()
                    }

                    val percent = (((pageIndex - 1).toFloat() / (totalPages - 2).coerceAtLeast(1)) * 100)
                        .roundToInt().coerceIn(0, 100)
                    val done = serial
                    runOnUiThread {
                        progress.progress = percent
                        status.text = "Processing PDF page ${pageIndex + 1}/$totalPages • $done cards saved"
                    }
                }

                completed = serial > 0
                check(completed) { "No voter cards were extracted from the PDF" }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    status.text = "Import stopped because the device ran out of memory. Close other apps and try again."
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                    progress.visibility = View.GONE
                }
            } finally {
                try { renderer?.close() } catch (_: Exception) { }
                try { pfd?.close() } catch (_: Exception) { }
                try { ocr?.recycle() } catch (_: Exception) { }
            }

            if (completed) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "${db.count()} voter-card entries saved. Search is available offline."
                    Toast.makeText(this, "Import complete", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadLanguageData(directory: File, language: String) {
        val target = File(directory, "$language.traineddata")
        if (target.exists() && target.length() > 100_000L) return

        val temporary = File(directory, "$language.traineddata.download")
        temporary.delete()
        val connection = URL(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$language.traineddata"
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 25_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Could not download $language OCR data (HTTP ${connection.responseCode})"
            }
            connection.inputStream.use { inputStream ->
                FileOutputStream(temporary).use { outputStream -> inputStream.copyTo(outputStream) }
            }
            check(temporary.length() > 100_000L) { "Downloaded $language OCR data is incomplete" }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not install $language OCR data" }
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun search() {
        val query = input.text.toString().trim()
        results.removeAllViews()
        if (query.isEmpty()) {
            status.text = "Enter a name, voter ID or serial number."
            return
        }

        val matches = db.search(query)
        status.text = "${matches.size} matching cards. Duplicate names are listed separately."
        matches.forEach { voter ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(3, 10, 3, 10)
            }
            val photo = ImageView(this).apply {
                val imageFile = File(voter.imagePath)
                if (imageFile.exists()) setImageURI(Uri.fromFile(imageFile))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(photo, LinearLayout.LayoutParams(110, 130))
            row.addView(
                TextView(this).apply {
                    text = "Serial ${voter.serial} • PDF page ${voter.pdfPage}\n${voter.ocrText}"
                    textSize = 14f
                    setPadding(10, 3, 3, 3)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            results.addView(row)
            results.addView(View(this).apply {
                setBackgroundColor(0xFFDDDDDD.toInt())
            }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

data class VoterRow(
    val serial: Int,
    val pdfPage: Int,
    val ocrText: String,
    val imagePath: String
)

class VoterDb(context: android.content.Context) :
    android.database.sqlite.SQLiteOpenHelper(context, "voters.db", null, 1) {

    override fun onCreate(database: android.database.sqlite.SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE voters (serial INTEGER PRIMARY KEY, page INTEGER NOT NULL, ocr TEXT, image TEXT NOT NULL)"
        )
        database.execSQL("CREATE INDEX voter_text_idx ON voters(ocr)")
    }

    override fun onUpgrade(
        database: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Version 1 schema is used by this app.
    }

    fun clear() {
        writableDatabase.delete("voters", null, null)
        File(contextFilesDir, "cards").deleteRecursively()
    }

    private val contextFilesDir: File = context.filesDir

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM voters", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun insert(serial: Int, pdfPage: Int, text: String, imagePath: String) {
        val values = android.content.ContentValues().apply {
            put("serial", serial)
            put("page", pdfPage)
            put("ocr", text)
            put("image", imagePath)
        }
        val result = writableDatabase.insert("voters", null, values)
        check(result != -1L) { "Could not save voter card $serial to the database" }
    }

    fun search(query: String): List<VoterRow> {
        val output = mutableListOf<VoterRow>()
        val pattern = "%$query%"
        readableDatabase.rawQuery(
            "SELECT serial, page, ocr, image FROM voters " +
                "WHERE ocr LIKE ? OR CAST(serial AS TEXT) LIKE ? " +
                "ORDER BY serial LIMIT 500",
            arrayOf(pattern, pattern)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output.add(
                    VoterRow(
                        serial = cursor.getInt(0),
                        pdfPage = cursor.getInt(1),
                        ocrText = cursor.getString(2).orEmpty(),
                        imagePath = cursor.getString(3).orEmpty()
                    )
                )
            }
        }
        return output
    }
}
