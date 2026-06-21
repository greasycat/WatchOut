package io.greasycat.watchout

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Initializes the default FirebaseApp at runtime from user-pasted config, so no
 * build-time google-services.json (and no per-user APK rebuild) is needed.
 */
object FcmInit {
    /** Ensure the default FirebaseApp matches the stored config. Returns true if FCM is ready. */
    fun ensure(context: Context): Boolean {
        if (!Prefs.fcmConfigured(context)) return false
        val options = FirebaseOptions.Builder()
            .setProjectId(Prefs.fcmProjectId(context))
            .setApplicationId(Prefs.fcmAppId(context))
            .setApiKey(Prefs.fcmApiKey(context))
            .setGcmSenderId(Prefs.fcmSenderId(context))
            .build()
        val existing = FirebaseApp.getApps(context)
            .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        return try {
            if (existing != null) {
                if (existing.options.applicationId == options.applicationId &&
                    existing.options.projectId == options.projectId
                ) return true
                existing.delete() // config changed — recreate
            }
            FirebaseApp.initializeApp(context, options)
            true
        } catch (e: Exception) {
            Log.e("FcmInit", "init failed: ${e.message}")
            false
        }
    }
}
