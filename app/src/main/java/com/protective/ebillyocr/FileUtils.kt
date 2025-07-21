// FileUtils.kt
package com.protective.ebillyocr

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Utility class for file operations
 * Handles storage operations with compatibility across Android versions
 */
object FileUtils {
    private const val TAG = "FileUtils"

    /**
     * Creates or updates a file for saving image data
     *
     * @param context Application context
     * @param fileName Name of the file to create or update
     * @param folderName Name of the folder to create file in
     * @param mimeType MIME type of the file
     * @return Pair of URI and OutputStream for the file
     */
    fun createOrUpdateImageFile(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String = "image/jpeg"
    ): Pair<Uri?, OutputStream?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "createOrUpdateFileMediaStore Function Called")
            createOrUpdateFileMediaStore(context, fileName, folderName, mimeType)
        } else {
            Log.d(TAG, "createOrUpdateFileLegacy Function Called")
            createOrUpdateFileLegacy(context, fileName, folderName, mimeType)
        }
    }

    /**
     * Creates or updates a file for JSON metadata
     *
     * @param context Application context
     * @param fileName Name of the file to create or update
     * @param folderName Name of the folder to create file in
     * @return Pair of URI and OutputStream for the file
     */
    fun createOrUpdateJsonFile(
        context: Context,
        fileName: String,
        folderName: String
    ): Pair<Uri?, OutputStream?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createOrUpdateFileMediaStore(
                context,
                fileName,
                folderName,
                "application/json",
                Environment.DIRECTORY_DOCUMENTS
            )
        } else {
            createOrUpdateFileLegacy(
                context,
                fileName,
                folderName,
                "application/json",
                Environment.DIRECTORY_DOCUMENTS
            )
        }
    }

    /**
     * Creates or updates a file using MediaStore API (Android 10+)
     */
    private fun createOrUpdateFileMediaStore(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String,
        directory: String = Environment.DIRECTORY_PICTURES
    ): Pair<Uri?, OutputStream?> {
        val TAG = "FileMediaStore"
        val contentResolver: ContentResolver = context.contentResolver

        Log.d(TAG, "Starting createOrUpdateFileMediaStore for $fileName in $folderName")

        // Define the content URI based on the mime type
        val contentUri = when {
            mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        // Extract the base name without extension for more robust searching
        val dotIndex = fileName.lastIndexOf('.')
        val baseFileName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        Log.d(TAG, "Base file name: $baseFileName, extension: $extension")

        // Delete ALL files that start with the base name pattern (including numbered versions)
        // This will catch "filename.jpg", "filename(1).jpg", etc.
        val selectionWildcard = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgsWildcard = arrayOf("$baseFileName%$extension", "$directory/$folderName")

        Log.d(TAG, "Searching for any files matching pattern: $baseFileName%$extension in $directory/$folderName")

        val filesToDelete = mutableListOf<Uri>()

        contentResolver.query(
            contentUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            selectionWildcard,
            selectionArgsWildcard,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                val uri = Uri.withAppendedPath(contentUri, id.toString())
                filesToDelete.add(uri)
                Log.d(TAG, "Found file to delete: $name with ID: $id, URI: $uri")
            }
        }

        // Delete all found files
        var totalDeleted = 0
        filesToDelete.forEach { uri ->
            try {
                val deleted = contentResolver.delete(uri, null, null)
                totalDeleted += deleted
                Log.d(TAG, "Deleted file URI: $uri, result: $deleted")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file $uri: ${e.message}")
            }
        }

        Log.d(TAG, "Total deleted files: $totalDeleted")

        // Create a new file with the exact name we want
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$folderName")
        }

        Log.d(TAG, "Creating new file with name: $fileName")

        return try {
            val uri = contentResolver.insert(contentUri, contentValues)
            Log.d(TAG, "New file created with URI: $uri")

            val outputStream = uri?.let {
                val stream = contentResolver.openOutputStream(it)
                Log.d(TAG, "OutputStream opened successfully: ${stream != null}")
                stream
            }

            uri to outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create file in MediaStore: ${e.message}", e)
            null to null
        }
    }

    /**
     * Creates or updates a file using legacy file system methods (Android 9 and below)
     */
    private fun createOrUpdateFileLegacy(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String,
        directory: String = Environment.DIRECTORY_PICTURES
    ): Pair<Uri?, OutputStream?> {
        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(directory),
            folderName
        )

        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: $storageDir")
                return null to null
            }
        }

        return try {
            val file = File(storageDir, fileName)
            // File will be overwritten if it exists
            val outputStream = FileOutputStream(file)
            Uri.fromFile(file) to outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create file: ${e.message}")
            null to null
        }
    }

    /**
     * Checks if a file exists at the given absolute path, deletes it if found,
     * and creates a fresh file
     *
     * @param absolutePath Absolute path of the file
     * @param mimeType MIME type for the new file (optional)
     * @return Pair of URI and OutputStream for the new file, or null if creation fails
     */
    fun checkAndReplaceFileByPath(
        absolutePath: String,
        mimeType: String = "application/octet-stream"
    ): Pair<Uri?, OutputStream?> {
        val file = File(absolutePath)

        // Check if file exists and delete it
        if (file.exists()) {
            Log.d(TAG, "File exists at path: $absolutePath - deleting")
            try {
                val deleted = file.delete()
                if (!deleted) {
                    Log.e(TAG, "Failed to delete existing file at: $absolutePath")
                    return null to null
                }
                Log.d(TAG, "Successfully deleted file at: $absolutePath")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file at $absolutePath: ${e.message}")
                return null to null
            }
        }

        // Create the directory if it doesn't exist
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: ${parentDir.absolutePath}")
                return null to null
            }
        }

        // Create fresh file
        return try {
            val outputStream = FileOutputStream(file)
            Log.d(TAG, "Successfully created fresh file at: $absolutePath")
            Uri.fromFile(file) to outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create fresh file at $absolutePath: ${e.message}")
            null to null
        }
    }

    /**
     * Checks if a file exists at the given path, returns true if it exists
     *
     * @param absolutePath Absolute path of the file
     * @return Boolean indicating whether the file exists
     */
    fun fileExistsAtPath(absolutePath: String): Boolean {
        val file = File(absolutePath)
        val exists = file.exists()
        Log.d(TAG, "File exists at $absolutePath: $exists")
        return exists
    }

    // Keep the original methods for backward compatibility
    fun createImageFile(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String = "image/jpeg"
    ): Pair<Uri?, OutputStream?> {
        return createOrUpdateImageFile(context, fileName, folderName, mimeType)
    }

    fun createJsonFile(
        context: Context,
        fileName: String,
        folderName: String
    ): Pair<Uri?, OutputStream?> {
        return createOrUpdateJsonFile(context, fileName, folderName)
    }
}