package org.fossify.filemanager.helpers.compression

import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

data class SmartArchiveEntry(
    val id: String,
    val nameInArchive: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val isDirectory: Boolean = false,
    val openSource: (() -> InputStream)? = null,
    val readSourceMetadata: (() -> Pair<Long, Long>)? = null
)

interface SmartArchiveTarget {
    val finalName: String
    val temporaryName: String
    fun openOutput(): OutputStream
    fun openInput(): InputStream
    /** Returns the finalized destination length, or -1 when the provider cannot report it. */
    fun actualSizeBytes(): Long
    /** Must fail if the final name already exists; it must never replace an existing file. */
    fun publish()
    fun deleteTemporary(): Boolean
}

data class SmartCompressionProgress(
    val stage: Stage,
    val currentArchive: Int,
    val completedArchives: Int,
    val processedEntries: Int,
    val totalEntries: Int
) {
    enum class Stage { SCANNING, COMPRESSING, VERIFYING, PUBLISHING }
}

data class SmartCompressionResult(val archivePaths: List<String>, val entryCount: Int)

class SmartCompressionException(
    message: String,
    val completedArchives: List<String> = emptyList(),
    cause: Throwable? = null
) : IOException(message, cause)

/** Creates chronological, independent ZIP files and validates each archive before publishing it. */
object SmartBatchCompressor {
    private const val BUFFER_SIZE = 128 * 1024
    private const val ARCHIVE_OVERHEAD_ESTIMATE = 256L * 1024L
    private const val ENTRY_OVERHEAD_ESTIMATE = 4096L
    private const val DEFLATE_BLOCK_SIZE = 16_384L
    private const val DEFLATE_BLOCK_OVERHEAD = 5L
    private const val ZIP_TIMESTAMP_TOLERANCE_MILLIS = 2_000L

    fun compress(
        entries: List<SmartArchiveEntry>,
        exclusiveLimitBytes: Long,
        password: String?,
        createTarget: (archiveNumber: Int) -> SmartArchiveTarget,
        isCancelled: () -> Boolean = { false },
        onProgress: (SmartCompressionProgress) -> Unit = {}
    ): SmartCompressionResult {
        require(exclusiveLimitBytes > 0) { "The archive size limit must be positive." }
        if (entries.isEmpty()) return SmartCompressionResult(emptyList(), 0)

        val ordered = entries.sortedWith(
            compareByDescending<SmartArchiveEntry> { it.modifiedMillis }
                .thenBy { it.nameInArchive }
                .thenBy { it.id }
        )
        validateEntries(ordered)
        val sourceDigests = preflight(ordered, isCancelled, onProgress)
        val published = mutableListOf<String>()
        val archivedIds = HashSet<String>()
        var start = 0
        var processed = 0
        val totalFiles = ordered.count { !it.isDirectory }
        var archiveNumber = 1

        while (start < ordered.size) {
            checkCancelled(isCancelled, published)
            var end = start
            var estimatedSize = ARCHIVE_OVERHEAD_ESTIMATE
            while (end < ordered.size) {
                val entryCost = estimatedContribution(ordered[end])
                if (end > start && safeAdd(estimatedSize, entryCost) >= exclusiveLimitBytes) break
                estimatedSize = safeAdd(estimatedSize, entryCost)
                end++
            }
            if (end == start) end = start + 1

            var candidateEnd = end
            while (true) {
                checkCancelled(isCancelled, published)
                val batch = ordered.subList(start, candidateEnd)
                val target = try {
                    createTarget(archiveNumber)
                } catch (e: Exception) {
                    throw failure("Couldn't prepare archive $archiveNumber: ${e.messageOrType()}", published, e)
                }

                val actualBytes = try {
                    onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.COMPRESSING, archiveNumber, published.size, processed, totalFiles))
                    writeArchive(batch, target, exclusiveLimitBytes, password, sourceDigests, isCancelled) { current ->
                        onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.COMPRESSING, archiveNumber, published.size, processed + current, totalFiles))
                    }
                } catch (e: LimitExceededException) {
                    deleteTemporaryOrFail(target, published)
                    if (candidateEnd == start + 1) {
                        throw failure(
                            "${batch.first().nameInArchive} cannot fit below the selected limit as a standalone ZIP. Safe split-archive output is unavailable in this stream-based storage flow, so no archive was published for this file.",
                            published,
                            e
                        )
                    }
                    candidateEnd--
                    continue
                } catch (e: CancellationException) {
                    deleteTemporaryOrFail(target, published)
                    throw failure("Compression cancelled. Previously verified archives were kept.", published, e)
                } catch (e: Exception) {
                    deleteTemporaryOrFail(target, published)
                    throw failure("Couldn't create archive $archiveNumber: ${e.messageOrType()}", published, e)
                }

                if (actualBytes >= exclusiveLimitBytes) {
                    deleteTemporaryOrFail(target, published)
                    if (candidateEnd == start + 1) {
                        throw failure("${batch.first().nameInArchive} cannot fit below the selected limit as a standalone ZIP. No archive was published for this file.", published)
                    }
                    candidateEnd--
                    continue
                }

                var fullyVerified = false
                try {
                    verifyDirectorySnapshots(ordered, isCancelled)
                    onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.VERIFYING, archiveNumber, published.size, processed, totalFiles))
                    verifyArchive(batch, target, password, sourceDigests, isCancelled) { current ->
                        onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.VERIFYING, archiveNumber, published.size, processed + current, totalFiles))
                    }
                    if (target.actualSizeBytes() != actualBytes || target.actualSizeBytes() >= exclusiveLimitBytes) {
                        throw IOException("The finalized archive size could not be confirmed or reached the configured limit.")
                    }
                    verifyDirectorySnapshots(ordered, isCancelled)
                    fullyVerified = true
                    checkCancelled(isCancelled, published)
                    onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.PUBLISHING, archiveNumber, published.size, processed + batch.count { !it.isDirectory }, totalFiles))
                    target.publish()
                } catch (e: Exception) {
                    if (fullyVerified) {
                        throw failure("Archive ${target.finalName} is verified but could not be published. Its recoverable temporary file remains at ${target.temporaryName}.", published, e)
                    }
                    deleteTemporaryOrFail(target, published)
                    throw failure("Couldn't verify archive ${target.finalName}: ${e.messageOrType()}", published, e)
                }

                published.add(target.finalName)
                if (batch.any { !archivedIds.add(it.id) }) {
                    throw failure("An input item was represented more than once; the archive set is incomplete.", published)
                }
                processed += batch.count { !it.isDirectory }
                start = candidateEnd
                archiveNumber++
                break
            }
        }

        if (processed != totalFiles || archivedIds != ordered.map { it.id }.toSet() || published.isEmpty()) {
            throw failure("The archive set is incomplete; not every selected item was accounted for.", published)
        }
        return SmartCompressionResult(published.toList(), processed)
    }

    private fun validateEntries(entries: List<SmartArchiveEntry>) {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        entries.forEach { entry ->
            if (entry.id.isBlank() || !ids.add(entry.id)) throw IOException("Duplicate source item detected.")
            val name = entry.nameInArchive
            if (name.isBlank() || name.startsWith('/') || '\\' in name || name.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
                throw IOException("Unsafe archive path: ${entry.nameInArchive}")
            }
            if (entry.sizeBytes < 0L) throw IOException("Unknown size for ${entry.nameInArchive}.")
            if (entry.isDirectory && entry.openSource != null) throw IOException("Directory entries cannot have file data.")
            if (!entry.isDirectory && (entry.openSource == null || entry.readSourceMetadata == null)) {
                throw IOException("Source access is unavailable for ${entry.nameInArchive}.")
            }
            val collisionKey = name.trimEnd('/').lowercase(Locale.ROOT)
            if (!names.add(collisionKey)) throw IOException("Duplicate archive path: ${entry.nameInArchive}")
        }
    }

    private fun preflight(
        entries: List<SmartArchiveEntry>,
        isCancelled: () -> Boolean,
        onProgress: (SmartCompressionProgress) -> Unit
    ): Map<String, ByteArray> {
        val digests = HashMap<String, ByteArray>()
        var processedFiles = 0
        val totalFiles = entries.count { !it.isDirectory }
        entries.forEach { entry ->
            checkCancelled(isCancelled, emptyList())
            if (entry.isDirectory) {
                val snapshot = entry.readSourceMetadata?.invoke() ?: return@forEach
                if (snapshot.second != entry.modifiedMillis) throw IOException("Folder ${entry.nameInArchive} changed after scanning.")
                return@forEach
            }
            onProgress(SmartCompressionProgress(SmartCompressionProgress.Stage.SCANNING, 0, 0, processedFiles, totalFiles))
            val before = entry.readSourceMetadata!!.invoke()
            if (before.first != entry.sizeBytes || before.second != entry.modifiedMillis) {
                throw IOException("${entry.nameInArchive} changed after scanning; retry the backup.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            entry.openSource!!.invoke().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled, emptyList())
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = safeAdd(total, count.toLong())
                    digest.update(buffer, 0, count)
                }
            }
            val after = entry.readSourceMetadata.invoke()
            if (total != entry.sizeBytes || before != after) {
                throw IOException("${entry.nameInArchive} changed or became unreadable during preflight.")
            }
            digests[entry.id] = digest.digest()
            processedFiles++
        }
        return digests
    }

    private fun estimatedContribution(entry: SmartArchiveEntry): Long {
        val nameBytes = entry.nameInArchive.toByteArray(Charsets.UTF_8).size.toLong()
        if (entry.isDirectory) return ENTRY_OVERHEAD_ESTIMATE + nameBytes * 2
        val deflateAllowance = (entry.sizeBytes / DEFLATE_BLOCK_SIZE + 1) * DEFLATE_BLOCK_OVERHEAD
        return safeAdd(safeAdd(entry.sizeBytes, deflateAllowance), ENTRY_OVERHEAD_ESTIMATE + nameBytes * 2)
    }

    private fun writeArchive(
        entries: List<SmartArchiveEntry>,
        target: SmartArchiveTarget,
        limitBytes: Long,
        password: String?,
        sourceDigests: Map<String, ByteArray>,
        isCancelled: () -> Boolean,
        onEntry: (Int) -> Unit
    ): Long {
        val counter = LimitOutputStream(target.openOutput(), limitBytes)
        try {
            (password?.let { Zip4jOutputStream(counter, it.toCharArray()) } ?: Zip4jOutputStream(counter)).use { zip ->
                var filesProcessed = 0
                entries.forEach { entry ->
                    checkCancelled(isCancelled, emptyList())
                    val name = if (entry.isDirectory) entry.nameInArchive.trimEnd('/') + "/" else entry.nameInArchive
                    val params = ZipParameters().apply {
                        fileNameInZip = name
                        lastModifiedFileTime = entry.modifiedMillis
                        if (password != null) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                        }
                    }
                    zip.putNextEntry(params)
                    if (!entry.isDirectory) {
                        val before = entry.readSourceMetadata!!.invoke()
                        if (before.first != entry.sizeBytes || before.second != entry.modifiedMillis) {
                            throw IOException("${entry.nameInArchive} changed before compression.")
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        entry.openSource!!.invoke().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                checkCancelled(isCancelled, emptyList())
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                total = safeAdd(total, count.toLong())
                            }
                        }
                        val after = entry.readSourceMetadata.invoke()
                        if (total != entry.sizeBytes || before != after || !digest.digest().contentEquals(sourceDigests[entry.id])) {
                            throw IOException("${entry.nameInArchive} changed during compression; archive discarded.")
                        }
                    }
                    zip.closeEntry()
                    if (!entry.isDirectory) {
                        filesProcessed++
                        onEntry(filesProcessed)
                    }
                }
            }
        } catch (e: LimitExceededException) {
            runCatching { counter.close() }
            throw e
        }
        return counter.count
    }

    private fun verifyArchive(
        entries: List<SmartArchiveEntry>,
        target: SmartArchiveTarget,
        password: String?,
        sourceDigests: Map<String, ByteArray>,
        isCancelled: () -> Boolean,
        onEntry: (Int) -> Unit
    ) {
        try {
            val input = target.openInput()
            val zip = password?.let { Zip4jInputStream(input, it.toCharArray()) } ?: Zip4jInputStream(input)
            zip.use {
                var filesVerified = 0
                entries.forEach { expected ->
                    checkCancelled(isCancelled, emptyList())
                    val header = zip.nextEntry ?: throw ZipVerificationException("Missing entry ${expected.nameInArchive}.")
                    val expectedName = if (expected.isDirectory) expected.nameInArchive.trimEnd('/') + "/" else expected.nameInArchive
                    if (header.fileName != expectedName || header.isDirectory != expected.isDirectory) {
                        throw ZipVerificationException("Unexpected entry in the finalized archive.")
                    }
                    if (kotlin.math.abs(header.lastModifiedTimeEpoch - expected.modifiedMillis) > ZIP_TIMESTAMP_TOLERANCE_MILLIS) {
                        throw ZipVerificationException("Timestamp verification failed for ${expected.nameInArchive}.")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkCancelled(isCancelled, emptyList())
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total = safeAdd(total, count.toLong())
                        if (!expected.isDirectory) digest.update(buffer, 0, count)
                    }
                    if (total != expected.sizeBytes) throw ZipVerificationException("Size verification failed for ${expected.nameInArchive}.")
                    if (!expected.isDirectory && !digest.digest().contentEquals(sourceDigests[expected.id])) {
                        throw ZipVerificationException("Content verification failed for ${expected.nameInArchive}.")
                    }
                    if (!expected.isDirectory) {
                        filesVerified++
                        onEntry(filesVerified)
                    }
                }
                if (zip.nextEntry != null) throw ZipVerificationException("The archive contains an unexpected extra entry.")
            }
        } catch (e: ZipVerificationException) {
            throw e
        } catch (e: Exception) {
            throw ZipVerificationException("ZIP integrity verification failed: ${e.messageOrType()}", e)
        }
    }

    private fun verifyDirectorySnapshots(entries: List<SmartArchiveEntry>, isCancelled: () -> Boolean) {
        entries.asSequence().filter { it.isDirectory }.forEach { directory ->
            checkCancelled(isCancelled, emptyList())
            val snapshot = directory.readSourceMetadata?.invoke() ?: return@forEach
            if (snapshot.second != directory.modifiedMillis) {
                throw IOException("Folder ${directory.nameInArchive} changed during compression; backup stopped.")
            }
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean, published: List<String>) {
        if (isCancelled()) throw CancellationException("Cancelled; ${published.size} verified archive(s) remain.")
    }

    private fun failure(message: String, published: List<String>, cause: Throwable? = null) =
        SmartCompressionException(
            if (published.isEmpty()) message else "$message ${published.size} previously verified archive(s) remain at their final names.",
            published.toList(),
            cause
        )

    private fun deleteTemporaryOrFail(target: SmartArchiveTarget, published: List<String>) {
        if (!runCatching { target.deleteTemporary() }.getOrDefault(false)) {
            throw failure("An incomplete temporary archive could not be removed: ${target.temporaryName}. Do not treat it as a backup.", published)
        }
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private fun Throwable.messageOrType() = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private class ZipVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private class LimitExceededException : IOException("The archive reached the exclusive size limit.")

    private class LimitOutputStream(private val delegate: OutputStream, private val limit: Long) : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            ensureCapacity(length.toLong())
            delegate.write(buffer, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun ensureCapacity(incoming: Long) {
            if (incoming >= limit - count) throw LimitExceededException()
        }
    }
}
