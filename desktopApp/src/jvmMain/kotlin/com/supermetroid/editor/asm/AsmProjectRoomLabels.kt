package com.supermetroid.editor.asm

/** Durable project identity in symbols; never a preview or allocated bank-$8F address. */
internal object AsmProjectRoomLabels {
    fun identities(project: com.supermetroid.editor.data.SmEditProject): Map<Int, String> {
        val rooms = project.newRooms.filter { it.previewRoomId != 0 }
        require(rooms.map { it.previewRoomId }.distinct().size == rooms.size && rooms.map { it.id }.distinct().size == rooms.size) {
            "Ambiguous project room source identities."
        }
        return rooms.associate { it.previewRoomId to it.id }
    }
    fun encoded(id: String): String {
        require(id.isNotBlank()) { "Project room identity is empty." }
        return id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).uppercase().padStart(2, '0') }
    }
    fun decode(encoded: String): String? {
        if (encoded.isEmpty() || encoded.length % 2 != 0 || !encoded.matches(Regex("[0-9A-F]+"))) return null
        val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val value = bytes.toString(Charsets.UTF_8)
        return value.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
    }
    fun prefix(id: String) = "SmeditProjectRoom_${encoded(id)}"
    fun entry(id: String) = prefix(id) + "_Entry"
    fun identity(label: String): String? = Regex("SmeditProjectRoom_([0-9A-F]+)_.+").matchEntire(label)?.groupValues?.get(1)?.let(::decode)
}
