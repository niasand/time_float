package com.timefloat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 开机自启：仅在悬浮窗权限已授予且用户开关为开时恢复服务。
 * BOOT_COMPLETED 广播对 Android 12+ 后台启动前台服务的限制有豁免。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) return
        if (!Prefs.isEnabled(context)) return
        OverlayService.start(context)
    }
}
