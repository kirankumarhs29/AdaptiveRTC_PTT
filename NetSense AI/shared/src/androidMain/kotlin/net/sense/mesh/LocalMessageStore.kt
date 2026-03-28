package net.sense.mesh

import android.content.Context

class LocalMessageStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "netsense_local_chat"
        private const val KEY_HISTORY = "chat_history"
        private const val FIELD_SEPARATOR = "\u001F"
        private const val ENTRY_SEPARATOR = "\n"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<ChatLogEntry> {
        val raw = prefs.getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()

        return raw.split(ENTRY_SEPARATOR)
            .asSequence()
            .mapNotNull { decode(it) }
            .toList()
    }

    fun append(entry: ChatLogEntry) {
        val existing = prefs.getString(KEY_HISTORY, "") ?: ""
        val encoded = encode(entry)
        val next = if (existing.isBlank()) encoded else "$existing$ENTRY_SEPARATOR$encoded"
        prefs.edit().putString(KEY_HISTORY, next).apply()
    }

    private fun encode(entry: ChatLogEntry): String {
        val text = entry.text
            .replace("%", "%25")
            .replace(FIELD_SEPARATOR, "%1F")
            .replace("\n", "%0A")

        return listOf(
            entry.peerId,
            entry.direction.name,
            entry.timestampEpochMs.toString(),
            text
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun decode(raw: String): ChatLogEntry? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size < 4) return null

        val peerId = parts[0]
        val direction = runCatching { ChatDirection.valueOf(parts[1]) }.getOrNull() ?: return null
        val timestamp = parts[2].toLongOrNull() ?: return null
        val text = parts.subList(3, parts.size)
            .joinToString(FIELD_SEPARATOR)
            .replace("%0A", "\n")
            .replace("%1F", FIELD_SEPARATOR)
            .replace("%25", "%")

        return ChatLogEntry(
            peerId = peerId,
            text = text,
            direction = direction,
            timestampEpochMs = timestamp
        )
    }
}
