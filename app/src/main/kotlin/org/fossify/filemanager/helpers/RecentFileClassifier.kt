package org.fossify.filemanager.helpers

/** Classifies MediaStore files, using extensions to correct missing or generic MIME metadata. */
object RecentFileClassifier {
    enum class Type { IMAGE, VIDEO, OTHER }

    private val images = setOf("jpg", "jpeg", "jpe", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "svg", "dng", "raw", "arw", "cr2", "cr3", "nef", "nrw", "orf", "rw2", "raf", "pef", "srw", "3fr", "iiq", "k25", "kdc", "mos", "mrw", "x3f")
    private val videos = setOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "3gp", "3g2", "mpeg", "mpg", "mpe", "mpv", "wmv", "flv", "f4v", "mts", "m2ts", "ts", "vob", "ogv", "asf", "divx")

    fun classify(path: String, mimeType: String?): Type {
        val extension = path.substringAfterLast('.', "").lowercase()
        return when {
            extension in images -> Type.IMAGE
            extension in videos -> Type.VIDEO
            mimeType?.startsWith("image/", true) == true -> Type.IMAGE
            mimeType?.startsWith("video/", true) == true -> Type.VIDEO
            else -> Type.OTHER
        }
    }
}
