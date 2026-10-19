
package com.selim.ultragameengine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class EmergencyRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "com.selim.ultragameengine.ACTION_EMERGENCY_RESET") {
            val result = EngineManager.restoreOriginalSystemSettings(context)
            context.stopService(Intent(context, GameOverlayService::class.java))
            if (action == "com.selim.ultragameengine.ACTION_EMERGENCY_RESET") {
                Toast.makeText(context, result, Toast.LENGTH_LONG).show()
            }
        }
    }
}
