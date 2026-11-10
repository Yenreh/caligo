package com.yenreh.caligo.waits

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred

/** Whether the app may show a wait's notification, and the way to let it */
interface NotificationAccess {
    /** The runtime permission, which only Android 13 and later ask for */
    fun granted(): Boolean

    /** Ask for it; whether it was given */
    suspend fun request(): Boolean

    /** The app's notifications on, and its channels for waits */
    fun enabled(): Boolean

    /** The system's notification settings for the app */
    fun openSettings()
}

/**
 * [NotificationAccess] of the device. Asking takes an activity, which
 * [attach] lends it, as the location permission is asked.
 */
class AndroidNotificationAccess(private val context: Context, private val alerts: () -> Alerts) : NotificationAccess {
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    override fun granted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Ask from [activity] from now on; call in its onCreate */
    fun attach(activity: ComponentActivity) {
        val mine = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { given ->
            pending?.complete(given)
            pending = null
        }
        launcher = mine
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (launcher === mine) launcher = null
                if (activity.isFinishing) {
                    pending?.complete(granted())
                    pending = null
                }
            }
        })
    }

    override suspend fun request(): Boolean {
        if (granted()) return true
        val waiting = pending ?: run {
            val asking = launcher ?: return false
            CompletableDeferred<Boolean>().also {
                pending = it
                asking.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return waiting.await()
    }

    override fun enabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        WaitNotifications.channelOn(context, WaitChannel.WAITS) &&
        WaitNotifications.channelOn(context, WaitChannel.alert(alerts()))

    override fun openSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
