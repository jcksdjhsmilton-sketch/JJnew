package com.jj.jarvis

import android.app.Notification
import android.service.notification.NotificationListenerService

/** Permite ao JJ ler as notificações do celular. */
class JJNotifListener : NotificationListenerService() {
    companion object {
        @Volatile var inst: JJNotifListener? = null
    }

    override fun onListenerConnected() { inst = this }
    override fun onListenerDisconnected() { inst = null }

    fun dump(): String {
        val list = try { activeNotifications } catch (e: Exception) { null } ?: return "(sem acesso as notificacoes)"
        if (list.isEmpty()) return "Nenhuma notificacao."
        val sb = StringBuilder()
        for (s in list.take(15)) {
            val ex = s.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            sb.append("- [").append(s.packageName).append("] ").append(title).append(": ").append(text).append('\n')
        }
        return sb.toString()
    }
}
