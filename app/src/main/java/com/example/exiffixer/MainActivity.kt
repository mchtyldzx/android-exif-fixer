package com.example.exiffixer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.example.exiffixer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null
    private var job: Job? = null
    
    // Stats
    private var totalFiles = 0
    private var filesToFix = mutableListOf<DocumentFile>()
    
    // Supported Extensions
    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "webp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "3gp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check for All Files Access Permission (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
                Toast.makeText(this, "Please grant 'All Files Access' permission.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSelectFolder.setOnClickListener {
            openDirectory()
        }

        binding.btnFixDates.setOnClickListener {
            if (selectedUri != null && filesToFix.isNotEmpty()) {
                startFixingProcess()
            }
        }
    }

    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                selectedUri = uri
                scanDirectory(uri)
            }
        }
    }

    private fun openDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        directoryPickerLauncher.launch(intent)
    }

    private fun scanDirectory(uri: Uri) {
        binding.tvStatus("Scanning...", true)
        binding.btnFixDates.isEnabled = false
        
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            filesToFix.clear()
            val rootDir = DocumentFile.fromTreeUri(applicationContext, uri)
            
            if (rootDir != null && rootDir.isDirectory) {
                recursiveScan(rootDir)
            }
            
            withContext(Dispatchers.Main) {
                totalFiles = filesToFix.size
                binding.tvStats.text = "Found $totalFiles media files in ${rootDir?.name}"
                binding.btnFixDates.isEnabled = totalFiles > 0
                binding.tvStatus("Ready to fix $totalFiles files.", false)
                appendLog("Scan complete. Found $totalFiles candidates.")
            }
        }
    }

    private fun recursiveScan(dir: DocumentFile) {
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                recursiveScan(file)
            } else {
                val name = file.name?.lowercase() ?: continue
                val ext = name.substringAfterLast('.', "")
                if (PHOTO_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                    filesToFix.add(file)
                }
            }
        }
    }

    private fun startFixingProcess() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.progressBar.max = filesToFix.size
        binding.progressBar.progress = 0
        binding.btnFixDates.isEnabled = false
        binding.btnSelectFolder.isEnabled = false
        
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            var processed = 0
            var success = 0
            var failed = 0
            var skipped = 0

            for (file in filesToFix) {
                val ok = processFile(file)
                if (ok) success++ else failed++ // Note: logic inside processFile handles 'skipped' vs 'processed' usually, simplified here
                
                processed++
                withContext(Dispatchers.Main) {
                    binding.progressBar.progress = processed
                    binding.tvProgress.text = "$processed / ${filesToFix.size}"
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.INVISIBLE
                binding.tvProgress.visibility = View.INVISIBLE
                binding.btnFixDates.isEnabled = true
                binding.btnSelectFolder.isEnabled = true
                binding.tvStatus("Complete! Fixed:$success Failed:$failed", false)
                appendLog("Job Finished. Success: $success, Failed: $failed")
            }
        }
    }

    private fun processFile(file: DocumentFile): Boolean {
        try {
            val name = file.name?.lowercase() ?: return false
            val ext = name.substringAfterLast('.', "")
            
            var targetTime: Long? = null
            
            contentResolver.openInputStream(file.uri)?.use { inputStream ->
                if (PHOTO_EXTENSIONS.contains(ext)) {
                    targetTime = extractPhotoDate(inputStream)
                } else if (VIDEO_EXTENSIONS.contains(ext)) {
                    targetTime = extractVideoDate(file.uri)
                }
            }

            if (targetTime != null && targetTime!! > 0) {
                // Check if the current modification time is significantly different (> 1 second)
                if (Math.abs(file.lastModified() - targetTime!!) > 1000) {
                    
                    var success = false
                    
                    // 1. Attempt using java.io.File by resolving the absolute path.
                    // This is required for some devices (e.g., Xiaomi) where SAF update fails or is cached aggressively.
                    try {
                        val docId = android.provider.DocumentsContract.getDocumentId(file.uri)
                        val split = docId.split(":")
                        if (split.size >= 2) {
                            val type = split[0]
                            if ("primary".equals(type, ignoreCase = true)) {
                                val path = "/storage/emulated/0/" + split[1]
                                val javaFile = java.io.File(path)
                                if (javaFile.exists()) {
                                    val localSuccess = javaFile.setLastModified(targetTime!!)
                                    if (localSuccess) {
                                        success = true
                                        appendLog("FIXED (File): ${file.name} -> ${formatDate(targetTime!!)}")
                                        
                                        // Force MediaStore Update (Ensure Gallery reflects the change immediately)
                                        try {
                                            val values = android.content.ContentValues()
                                            values.put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, targetTime!! / 1000)
                                            
                                            // Update DATE_TAKEN for images
                                            if (PHOTO_EXTENSIONS.contains(ext)) {
                                                values.put(android.provider.MediaStore.Images.Media.DATE_TAKEN, targetTime!!)
                                            }
                                            
                                            contentResolver.update(file.uri, values, null, null)
                                        } catch(e: Exception) {
                                            // MediaStore update might fail on some restrictive OS versions, but File update succeeded.
                                        }
                                        
                                        // Trigger MediaScanner to refresh external applications (Gallery)
                                        android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(javaFile.absolutePath), null, null)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Path resolution failed, fall back to standard SAF
                    }

                    // 2. Fallback to standard SAF (ContentResolver) if File access failed
                    if (!success) {
                        try {
                             val updateValues = android.content.ContentValues()
                             updateValues.put(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED, targetTime!!)
                             val rows = contentResolver.update(file.uri, updateValues, null, null)
                             if (rows > 0) {
                                 success = true
                                 appendLog("FIXED (SAF): ${file.name} -> ${formatDate(targetTime!!)}")
                             }
                        } catch (e: Exception) {
                            appendLog("FAIL: ${file.name} - ${e.message}")
                        }
                    }
                    
                    return success
                } else {
                    return true // Already correct
                }
            } else {
                appendLog("SKIP: No metadata date for ${file.name}")
                return false
            }
        } catch (e: Exception) {
            appendLog("EXCEPTION: ${file.name} - ${e.message}")
            return false
        }
    }

    private fun extractPhotoDate(inputStream: InputStream): Long? {
        try {
            val exif = ExifInterface(inputStream)
            // Priority: DateTimeOriginal -> DateTime
            val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) 
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            
            if (dateString != null) {
                return parseExifDate(dateString)
            }
        } catch (e: Exception) {
             e.printStackTrace()
        }
        return null
    }

    private fun extractVideoDate(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(applicationContext, uri)
            // Common keys for date
            val dateString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (dateString != null) {
                // Format often: "19050101T000000.000Z" (ISO 8601) or "yyyyMMddTHHmmss"
                return parseIso8601(dateString)
            }
        } catch (e: Exception) {
             e.printStackTrace()
        } finally {
            retriever.release()
        }
        return null
    }
    
    // Helpers
    
    private fun parseExifDate(dateStr: String): Long? {
        // "yyyy:MM:dd HH:mm:ss"
        try {
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getDefault() // Assume local if no timezone, or Exif non-standard
            return sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseIso8601(dateStr: String): Long? {
        // Formats: "20260101T120000.000Z", "2027-01-01T12:00:00Z"
        val formats = arrayOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC") // ISO usually Z = UTC
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (e: Exception) {
                // continue
            }
        }
        return null
    }
    
    private fun formatDate(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    private fun ActivityMainBinding.tvStatus(msg: String, isWorking: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            tvLog.text = msg
            if(isWorking) {
                // could show spinner
            }
        }
    }

    private fun appendLog(msg: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val current = binding.tvLog.text.toString()
            val newLog = "$msg\n$current"
            // Keep log size reasonable
            if (newLog.length > 5000) {
                binding.tvLog.text = newLog.take(5000) + "..."
            } else {
                binding.tvLog.text = newLog
            }
        }
    }
}
