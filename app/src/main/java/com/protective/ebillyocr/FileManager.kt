
package com.protective.ebillyocr
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * FileManager handles all file storage operations
 *
 * This class encapsulates saving metadata files and
 * notifying the media scanner on different Android versions.
 */
class FileManager(private val context: Context) {
    private val tag = "FileManager"

    /**
     * Saves JSON metadata for the captured image
     * Always uses a single file that gets overwritten with each new save
     *
     * @param imageUri URI of the captured image
     * @param timestamp Formatted timestamp string
     * @param meterReading Optional meter reading detected from the image
     * @param savedFilename Optional filename (not used as we always use a fixed filename)
     * @param isEdited Whether the image has been edited
     */
    fun saveJsonMetadata(imagePath: String, timestamp: String, meterReading: String? = null, savedFilename: String? = null, isEdited: Boolean) {
        Log.d(tag, "Saving JSON metadata for image: $imagePath")

        try {
            val currentTimeMillis = System.currentTimeMillis()
            val formattedTimestamp = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US
            ).format(currentTimeMillis)

            // Create JSON object with metadata
            val metadata = JSONObject().apply {
                put("timestamp", timestamp)
                put("meterReading", meterReading ?: "")
                put("filename", imagePath)
                put("isEdited", isEdited)
                // Any other metadata fields you want to include
            }

            val jsonString = metadata.toString()
            // Use a fixed filename that will be reused for all saves
            //var jsonFileName = savedFilename

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10 and above
                saveMetadataModernApi(savedFilename.toString(), jsonString)
            } else {
                // For below Android 10
                saveMetadataLegacyApi(savedFilename.toString(), jsonString)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error saving JSON metadata: ${e.message}", e)
        }
    }

    /**
     * Saves metadata using modern MediaStore API (Android 10+)
     * Deletes existing file with the same name before creating a new one
     *
     * @param jsonFileName Name of the JSON file
     * @param jsonString Content of the JSON file
     */
    private fun saveMetadataModernApi(jsonFileName: String, jsonString: String) {
        val contentUri = MediaStore.Files.getContentUri("external")

        Log.d(tag, "content URL $contentUri")

        // First try to find and delete if the file already exists
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(
            jsonFileName,
            Environment.DIRECTORY_DOCUMENTS + File.separator + APP_FOLDER_NAME + File.separator
        )

        context.contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                // File exists, delete it
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val existingUri = Uri.withAppendedPath(contentUri, id.toString())
                context.contentResolver.delete(existingUri, null, null)
                Log.d(tag, "Deleted existing JSON metadata at: $existingUri")
            }
        }

        // Create a new file
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, jsonFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + File.separator + APP_FOLDER_NAME
            )
        }

        val jsonUri = context.contentResolver.insert(contentUri, contentValues)

        jsonUri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray())
            }
            Log.d(tag, "Created new JSON metadata at: $it")
        }
    }

    /**
     * Saves metadata using legacy file API (Android 9 and below)
     * Overwrites the file if it exists
     *
     * @param jsonFileName Name of the JSON file
     * @param jsonString Content of the JSON file
     */
    private fun saveMetadataLegacyApi(jsonFileName: String, jsonString: String) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            APP_FOLDER_NAME
        )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(directory, jsonFileName)
        FileOutputStream(file).use { it.write(jsonString.toByteArray()) }

        Log.d(tag, "JSON metadata saved at: ${file.absolutePath}")
    }

    /**
     * Makes the image available to gallery on older Android versions
     *
     * @param savedUri URI of the captured image
     */
    fun notifyGallery(savedUri: Uri) {
        // Only needed for Android 9 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(tag, "Notifying gallery for image: $savedUri")
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = savedUri
            context.sendBroadcast(mediaScanIntent)
        }
    }

    companion object {
        private const val APP_FOLDER_NAME = "npdcl"
    }
}

//package com.example.cameraxapp
//
//import android.content.ContentValues
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.os.Environment
//import android.provider.MediaStore
//import android.util.Log
//import org.json.JSONObject
//import java.io.File
//import java.io.FileOutputStream
//import java.text.SimpleDateFormat
//import java.util.Locale
//
///**
// * FileManager handles all file storage operations
// *
// * This class encapsulates saving metadata files and
// * notifying the media scanner on different Android versions.
// */
//class FileManager(private val context: Context) {
//    private val tag = "FileManager"
//
//    /**
//     * Saves JSON metadata for the captured image
//     *
//     * @param imageUri URI of the captured image
//     * @param timestamp Formatted timestamp string
//     * @param meterReading Optional meter reading detected from the image
//     */
//    fun saveJsonMetadata(imageUri: Uri, timestamp: String, meterReading: String? = null,savedFilename: String?=null, isEdited: Boolean) {
//        Log.d(tag, "Saving JSON metadata for image: $imageUri")
//
//        try {
//            val currentTimeMillis = System.currentTimeMillis()
//            val formattedTimestamp = SimpleDateFormat(
//                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
//                Locale.US
//            ).format(currentTimeMillis)
//
//            // Create JSON object with metadata
//            val metadata = JSONObject().apply {
//                put("timestamp", timestamp)
//                put("meterReading", meterReading ?: "")
//                put("filename", imageUri)
//                put("isEdited", isEdited)
//                // Any other metadata fields you want to include
//            }
//
//            val jsonString = metadata.toString()
//            val jsonFileName = "$savedFilename.json"
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                // For Android 10 and above
//                saveMetadataModernApi(jsonFileName, jsonString)
//            } else {
//                // For below Android 10
//                saveMetadataLegacyApi(jsonFileName, jsonString)
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Error saving JSON metadata: ${e.message}", e)
//        }
//    }
//
//    /**
//     * Saves metadata using modern MediaStore API (Android 10+)
//     *
//     * @param jsonFileName Name of the JSON file
//     * @param jsonString Content of the JSON file
//     */
//    private fun saveMetadataModernApi(jsonFileName: String, jsonString: String) {
//        val contentValues = ContentValues().apply {
//            put(MediaStore.MediaColumns.DISPLAY_NAME, jsonFileName)
//            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
//            put(
//                MediaStore.MediaColumns.RELATIVE_PATH,
//                Environment.DIRECTORY_DOCUMENTS + File.separator + APP_FOLDER_NAME
//            )
//        }
//
//        val contentUri = MediaStore.Files.getContentUri("external")
//        val jsonUri = context.contentResolver.insert(contentUri, contentValues)
//
//        jsonUri?.let {
//            context.contentResolver.openOutputStream(it)?.use { outputStream ->
//                outputStream.write(jsonString.toByteArray())
//            }
//
//            Log.d(tag, "JSON metadata saved at: $it")
//        }
//    }
//
//    /**
//     * Saves metadata using legacy file API (Android 9 and below)
//     *
//     * @param jsonFileName Name of the JSON file
//     * @param jsonString Content of the JSON file
//     */
//    private fun saveMetadataLegacyApi(jsonFileName: String, jsonString: String) {
//        val directory = File(
//            Environment.getExternalStoragePublicDirectory(
//                Environment.DIRECTORY_DOCUMENTS
//            ),
//            APP_FOLDER_NAME
//        )
//
//        if (!directory.exists()) {
//            directory.mkdirs()
//        }
//
//        val file = File(directory, jsonFileName)
//        FileOutputStream(file).use { it.write(jsonString.toByteArray()) }
//
//        Log.d(tag, "JSON metadata saved at: ${file.absolutePath}")
//    }
//
//
//    /**
//     * Makes the image available to gallery on older Android versions
//     *
//     * @param savedUri URI of the captured image
//     */
//    fun notifyGallery(savedUri: Uri) {
//        // Only needed for Android 9 and below
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
//            Log.d(tag, "Notifying gallery for image: $savedUri")
//            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
//            mediaScanIntent.data = savedUri
//            context.sendBroadcast(mediaScanIntent)
//        }
//    }
//
//    companion object {
//        private const val APP_FOLDER_NAME = "npdcl"
//    }
//}