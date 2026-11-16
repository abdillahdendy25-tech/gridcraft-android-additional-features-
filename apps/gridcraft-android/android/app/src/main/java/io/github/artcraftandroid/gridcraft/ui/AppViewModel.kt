// 1. State kunci posisi gambar/objek
var lockedObjectIds by mutableStateOf<Set<Long>>(emptySet())

fun isObjectLocked(id: Long): Boolean = id in lockedObjectIds

fun toggleObjectLock(id: Long) {
    objectDrag = null
    lockedObjectIds = if (id in lockedObjectIds) lockedObjectIds - id else lockedObjectIds + id
}

fun nudgeSelectedObject(dx: Float, dy: Float) {
    val o = selectedObject ?: return
    if (isObjectLocked(o.id.toLong())) return
    // Panggil fungsi pemindah objek yang sudah ada di AppViewModel (sesuaikan nama perintahnya jika berbeda)
    command("object.move", "{\"id\": ${o.id}, \"dx\": $dx, \"dy\": $dy}")
}

// 2. Deteksi gambar di Clipboard Android
private fun clipboardImageUri(): android.net.Uri? {
    val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    val hasImageMime = clip.description.hasMimeType("image/*")
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val type = appContext.contentResolver.getType(uri).orEmpty()
        if (hasImageMime || type.startsWith("image/")) return uri
    }
    return null
}
