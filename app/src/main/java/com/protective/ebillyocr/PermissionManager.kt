package com.protective.ebillyocr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * PermissionManager handles all permission-related operations
 *
 * This class abstracts the permission requesting logic for different Android versions
 * and provides callbacks for permission status.
 */
class PermissionManager(
    private val activity: AppCompatActivity,
    private val listener: PermissionListener
) {
    private val tag = "PermissionManager"

    // Interface for permission callbacks
    interface PermissionListener {
        fun onPermissionsGranted()
        fun onPermissionsDenied()
    }

    // Activity result launcher for requesting multiple permissions
    private val requestPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var allPermissionsGranted = true
            for (entry in permissions.entries) {
                if (!entry.value) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                listener.onPermissionsGranted()
            } else {
                listener.onPermissionsDenied()
            }
        }

    /**
     * Requests all necessary permissions based on Android version
     */
    fun requestPermissions() {
        Log.d(tag, "Requesting permissions")

        // Check if permissions are already granted
        if (allPermissionsGranted()) {
            Log.d(tag, "All permissions already granted")
            listener.onPermissionsGranted()
        } else {
            Log.d(tag, "Requesting permissions: ${getRequiredPermissions().joinToString()}")
            requestPermissionsLauncher.launch(getRequiredPermissions())
        }
    }

    /**
     * Checks if all required permissions are granted
     *
     * @return true if all permissions are granted, false otherwise
     */
    fun allPermissionsGranted(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(
                activity.baseContext,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Gets the required permissions based on Android version
     *
     * @return Array of required permissions
     */
    fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10, 11, 12
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
            else -> {
                // Android 9 and below
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
    }
}