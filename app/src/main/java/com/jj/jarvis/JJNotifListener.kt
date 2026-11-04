package com.jj.jarvis

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le as notificacoes do celular e guarda um HISTORICO (as ultimas 400 mensagens, cada uma com a hora), mesmo depois de
 * a notificacao sumir. Assim o JJ sabe qual e a mensagem MAIS RECENTE e quem mandou, em vez de adivinhar pela ordem da lista.
 */
class JJNotifListener : NotificationListenerService() {
    class Item(val app: String, val who: String, val text: String, val time: Long)

    companion object {
        @Volatile var inst: JJNotifListener? = null
        private val items = LinkedHashMap<String, Item>()

        fun add(i: Item) {
            synchronized(items) {
                items[i.app + "|" + i.who + "|" + i.time + "|" + i.text] = i
                while (items.size > 400) {
                    val it = items.keys.iterator()
                    it.next()
                    it.remove()
                }
            }
        }

        private fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    override fun onListenerConnected() {
        inst = this
        try { for (s in activeNotifications) record(s) } catch (e: Exception) {}
    }

    override fun onListenerDisconnected() { inst = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            try { record(sbn) } catch (e: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun record(sbn: StatusBarNotification) {
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            for (m in msgs) {
                val b = m as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString() ?: continue
                val t = b.getLong("time", sbn.postTime)
                val sender = b.getCharSequence("sender")?.toString() ?: ""
                add(Item(app, if (sender.isEmpty()) title else sender, text, t))
            }
            return
        }
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        if (title.isEmpty() && text.isEmpty()) return
        add(Item(app, title, text, sbn.postTime))
    }

    /** Mais recente primeiro, com hora. filter = nome do contato ou do app (sem acento/maiuscula). */
    fun dump(filter: String, limit: Int): String {
        val all: List<Item> = synchronized(items) { items.values.toList() }
        val q = norm(filter.trim())
        val sel = all.filter { q.isEmpty() || norm(it.app + " " + it.who + " " + it.text).contains(q) }
            .sortedByDescending { it.time }
            .take(limit.coerceIn(1, 30))
        if (sel.isEmpty()) {
            return "Nenhuma notificacao registrada" + (if (q.isNotEmpty()) " para \"$filter\"" else "") +
                " (so aparecem as recebidas com o JJ ativo). Para ver uma conversa, abra o app e use read_screen."
        }
        val loc = Locale("pt", "BR")
        val hm = SimpleDateFormat("HH:mm", loc)
        val dm = SimpleDateFormat("dd/MM HH:mm", loc)
        val today = SimpleDateFormat("yyyyMMdd", loc).format(Date())
        val ymd = SimpleDateFormat("yyyyMMdd", loc)
        val sb = StringBuilder("Agora sao ${hm.format(Date())}. Da mais recente para a mais antiga:\n")
        for (i in sel) {
            val d = Date(i.time)
            val stamp = if (ymd.format(d) == today) hm.format(d) else dm.format(d)
            sb.append('[').append(stamp).append("] ").append(i.app)
            if (i.who.isNotEmpty()) sb.append(" - ").append(i.who)
            sb.append(": ").append(i.text.replace("\n", " ").take(300)).append('\n')
        }
        return sb.toString().trim()
    }
}
