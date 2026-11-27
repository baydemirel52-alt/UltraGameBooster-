package com.selim.ultragameengine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class EmergencyRestoreReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EMERGENCY_RESET = "com.selim.ultragameengine.ACTION_EMERGENCY_RESET"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_EMERGENCY_RESET) return

        val appContext = context.applicationContext
        val isManualReset = action == ACTION_EMERGENCY_RESET

        // Shizuku komutları bloklayıcıdır; ana thread'i kilitlememek için arka planda çalıştır.
        val pending = goAsync()
        Thread {
            try {
                val result = SystemOptimizer.restoreOriginalSystemSettings(appContext, force = isManualReset)
                appContext.stopService(Intent(appContext, GameOverlayService::class.java))
                if (isManualReset) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, result, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
