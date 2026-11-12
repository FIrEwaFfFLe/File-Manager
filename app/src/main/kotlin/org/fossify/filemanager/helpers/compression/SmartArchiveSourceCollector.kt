package org.fossify.filemanager.helpers.compression

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getFileInputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.filemanager.extensions.config
import org.fossify.commons.models.FileDirItem
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException

/** Enumerates sources once before any temporary archive is created. */
class SmartArchiveSourceCollector(private val activity: BaseSimpleActivity) {
    private data class Child(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

    fun collect(
        selectedPaths: List<String>,
        isCancelled: () -> Boolean = { false },
        onScanned: (processedItems: Int) -> Unit = {}
    ): List<SmartArchiveEntry> {
        if (selectedPaths.isEmpty()) throw IOException("No files were selected.")
        val uniqueRoots = selectedPaths.distinctBy(::identity).sortedBy(::identity)
        val rootNames = buildRootNames(uniqueRoots)
        val entries = LinkedHashMap<String, SmartArchiveEntry>()
        val visitedDirectories = HashSet<String>()
        var scannedItems = 0

        fun walk(path: String, entryName: String, isDirectory: Boolean, size: Long, modified: Long) {
            if (isCancelled()) throw CancellationException("Scanning cancelled.")
            val id = identity(path)
            if (entries.containsKey(id)) return
            scannedItems++
            onScanned(scannedItems)
            if (modified <= 0L) throw IOException("Can't read the original modification time for $entryName.")
            if (isDirectory) {
                if (!visitedDirectories.add(id)) return
                entries[id] = SmartArchiveEntry(
                    id, entryName, 0L, modified, isDirectory = true,
                    readSourceMetadata = { metadata(path, 0L, modified) }
                )
                children(path).forEach { child ->
                    if (!activity.config.shouldShowHidden() && child.name.startsWith('.')) return@forEach
                    val childName = "$entryName/${child.name}"
                    walk(child.path, childName, child.isDirectory, child.size, child.modified)
                }
            } else {
                if (size < 0L) throw IOException("Can't determine the size of $entryName.")
                entries[id] = SmartArchiveEntry(
                    id = id,
                    nameInArchive = entryName,
                    sizeBytes = size,
                    modifiedMillis = modified,
                    openSource = {
                        activity.getFileInputStreamSync(path) ?: throw IOException("Can't read $entryName.")
                    },
                    readSourceMetadata = { metadata(path, size, modified) }
                )
            }
        }

        uniqueRoots.forEachIndexed { index, path ->
            val document = resolveDocument(path)
            val isDirectory = if (activity.isRestrictedSAFOnlyRoot(path)) activity.getIsPathDirectory(path)
            else document?.isDirectory ?: activity.getIsPathDirectory(path)
            val name = rootNames[index]
            val size = if (isDirectory) 0L else sourceLength(path, document)
            val modified = document?.lastModified()?.takeIf { it > 0L } ?: File(path).lastModified()
            walk(path, name, isDirectory, size, modified)
        }
        if (entries.isEmpty()) throw IOException("No readable files or folders were found.")
        return entries.values.toList()
    }

    private fun buildRootNames(paths: List<String>): List<String> {
        val names = paths.map { it.getFilenameFromPath().ifBlank { "item" } }
        val duplicateNames = names.groupingBy { it.lowercase(java.util.Locale.ROOT) }.eachCount()
        val result = paths.mapIndexed { index, path ->
            val name = names[index]
            if ((duplicateNames[name.lowercase(java.util.Locale.ROOT)] ?: 0) == 1) name
            else {
                val parent = path.getParentPath().getFilenameFromPath().ifBlank { "folder" }
                "$parent/$name"
            }
        }.toMutableList()
        val duplicateResolvedNames = result.withIndex().groupBy { it.value.lowercase(java.util.Locale.ROOT) }.filterValues { it.size > 1 }
        duplicateResolvedNames.values.forEach { collisions ->
            collisions.forEach { indexed ->
                val name = names[indexed.index]
                result[indexed.index] = "selected_${indexed.index + 1}/$name"
            }
        }
        return result
    }

    private fun children(path: String): List<Child> {
        if (activity.isRestrictedSAFOnlyRoot(path)) {
            val result = AtomicReference<ArrayList<FileDirItem>?>(null)
            val latch = CountDownLatch(1)
            activity.getAndroidSAFFileItems(path, true, false) {
                result.set(it)
                latch.countDown()
            }
            if (!latch.await(2, TimeUnit.MINUTES)) throw IOException("Timed out while reading the selected Android storage folder.")
            return (result.get() ?: throw IOException("Can't list the selected Android storage folder.")).map {
                Child(it.path, it.name, it.isDirectory, it.size, it.modified)
            }
        }

        val document = resolveDocument(path)
        if (activity.isPathOnOTG(path) || path.startsWith("content://") || document != null && !File(path).exists()) {
            val directory = document ?: throw IOException("Can't access folder $path.")
            if (!directory.canRead()) throw IOException("Permission denied reading folder $path.")
            return directory.listFiles()?.map { child ->
                val name = child.name ?: throw IOException("A storage provider returned an unnamed item.")
                val childPath = if (activity.isPathOnOTG(path)) "$path/$name" else child.uri.toString()
                Child(childPath, name, child.isDirectory,
                    if (child.isDirectory) 0L else child.length(), child.lastModified())
            } ?: throw IOException("Can't list folder $path.")
        }

        val directory = File(path)
        if (!directory.canRead()) throw IOException("Permission denied reading folder $path.")
        return directory.listFiles()?.map { child ->
            Child(child.absolutePath, child.name, child.isDirectory, if (child.isDirectory) 0L else child.length(), child.lastModified())
        } ?: throw IOException("Can't list folder $path.")
    }

    private fun sourceLength(path: String, document: DocumentFile?): Long {
        val file = File(path)
        if (file.exists()) return file.length()
        return document?.length() ?: -1L
    }

    private fun metadata(path: String, knownSize: Long, knownModified: Long): Pair<Long, Long> {
        val file = File(path)
        if (file.exists()) return file.length() to file.lastModified()
        val document = resolveDocument(path)
        if (document?.exists() == true && document.canRead()) {
            val length = document.length().takeIf { it > 0L || knownSize == 0L } ?: knownSize
            val modified = document.lastModified().takeIf { it > 0L } ?: knownModified
            return length to modified
        }
        // Some document providers permit stream access but expose no DocumentFile metadata.
        // Use the metadata captured from the provider listing; hashes still detect content changes.
        return knownSize to knownModified
    }

    private fun resolveDocument(path: String): DocumentFile? {
        if (path.startsWith("content://")) {
            return runCatching { DocumentFile.fromSingleUri(activity, Uri.parse(path)) }.getOrNull()
        }
        if (activity.isRestrictedSAFOnlyRoot(path)) return null
        if (File(path).exists() && !activity.isPathOnOTG(path)) return null
        return activity.getDocumentFile(path)
    }

    private fun identity(path: String): String {
        if (path.startsWith("content://")) return Uri.parse(path).normalizeScheme().toString()
        return runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)
    }
}
