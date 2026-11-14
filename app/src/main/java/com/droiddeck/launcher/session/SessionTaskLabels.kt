package com.droiddeck.launcher.session

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject

object SessionTaskLabels {
    fun panel(activity: Activity) = apply(activity, R.string.session_task_panel)

    fun steamVr(activity: Activity) = apply(activity, R.string.session_task_steamvr, R.drawable.ic_steamvr)

    @Suppress("DEPRECATION")
    private fun apply(activity: Activity, resource: Int, icon: Int = 0) {
        val label = activity.getString(resource)
        activity.title = label
        activity.setTaskDescription(
            if (Build.VERSION.SDK_INT >= 33) ActivityManager.TaskDescription.Builder()
                .setLabel(label).setIcon(icon).build()
            else ActivityManager.TaskDescription(label,
                if (icon != 0) BitmapFactory.decodeResource(activity.resources, icon) else null)
        )
    }

    @Suppress("DEPRECATION")
    fun diagnostics(context: Context): JSONArray = JSONArray().apply {
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@forEach
            put(JSONObject()
                .put("taskId", if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id)
                .put("label", info.taskDescription?.label ?: JSONObject.NULL)
                .put("baseActivity", info.baseActivity?.flattenToShortString() ?: JSONObject.NULL)
                .put("topActivity", info.topActivity?.flattenToShortString() ?: JSONObject.NULL))
        }
    }
}
