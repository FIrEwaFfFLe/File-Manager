package org.fossify.filemanager.helpers

import java.io.File

/** Rejects archive destinations that resolve outside the chosen extraction directory. */
object ZipPathSafety {
    fun isInsideDirectory(directory: String, candidate: String): Boolean = runCatching {
        val canonicalDirectory = File(directory).canonicalFile.path
        val canonicalCandidate = File(candidate).canonicalFile.path
        val directoryPrefix = if (canonicalDirectory.endsWith(File.separator)) {
            canonicalDirectory
        } else {
            canonicalDirectory + File.separator
        }
        canonicalCandidate == canonicalDirectory || canonicalCandidate.startsWith(directoryPrefix)
    }.getOrDefault(false)
}
