package org.fossify.filemanager.helpers.compression

import net.lingala.zip4j.io.inputstream.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.random.Random

class SmartBatchCompressorTest {
    private lateinit var root: File
    private lateinit var inputs: File
    private lateinit var outputs: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("smart-zip-test").toFile()
        inputs = File(root, "inputs").apply { mkdirs() }
        outputs = File(root, "outputs").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun createsVerifiedZipAndKeepsUnicodeNamesAndTimestamps() {
        val source = source("photo-ä.jpg", Random(7).nextBytes(32_000), 1_700_000_000_000L)
        val result = compress(listOf(source), 2 * MIB)

        assertEquals(1, result.archivePaths.size)
        val archive = File(result.archivePaths.single())
        assertTrue(archive.length() < 2 * MIB)
        ZipInputStream(FileInputStream(archive)).use { zip ->
            val entry = zip.nextEntry
            assertEquals("photo-ä.jpg", entry.fileName)
            assertEquals(source.modifiedMillis / 2_000 * 2_000, entry.lastModifiedTimeEpoch / 2_000 * 2_000)
            assertTrue(zip.readBytes().contentEquals(source.bytes))
            assertEquals(null, zip.nextEntry)
        }
        assertTrue(source.file.exists())
        assertTrue(source.file.readBytes().contentEquals(source.bytes))
    }

    @Test
    fun createsSeveralIndependentArchivesWhenGreedyBatchWouldExceedLimit() {
        val sources = (1..3).map { index ->
            source("photo_$index.jpg", Random(index).nextBytes(1_300_000), 1_700_000_000_000L - index * 10_000)
        }
        val result = compress(sources, 2 * MIB)

        assertTrue(result.archivePaths.size > 1)
        val recoveredNames = result.archivePaths.flatMap { path ->
            val names = mutableListOf<String>()
            ZipInputStream(FileInputStream(path)).use { zip ->
                while (true) {
                    val next = zip.nextEntry ?: break
                    names.add(next.fileName)
                }
            }
            assertTrue(File(path).length() < 2 * MIB)
            names
        }
        assertEquals(sources.sortedByDescending { it.modifiedMillis }.map { it.entryName }, recoveredNames)
        assertEquals(sources.size, recoveredNames.size)
    }

    @Test
    fun highlyCompressibleFileMayFitEvenWhenSourceExceedsLimit() {
        val source = source("large.txt", ByteArray(1_500_000), 1_700_000_000_000L)
        val result = compress(listOf(source), 1 * MIB)
        assertEquals(1, result.archivePaths.size)
        assertTrue(File(result.archivePaths.single()).length() < MIB)
    }

    @Test
    fun incompressibleSingleFileThatCannotFitFailsWithoutPublishing() {
        val source = source("video.bin", Random(29).nextBytes(1_300_000), 1_700_000_000_000L)
        try {
            compress(listOf(source), MIB)
            fail("Expected an oversized standalone file to be rejected")
        } catch (e: SmartCompressionException) {
            assertTrue(e.message.orEmpty().contains("cannot fit below the selected limit"))
        }
        assertTrue(outputs.listFiles().orEmpty().none { it.name.endsWith(".zip") && !it.name.contains("partial") })
        assertTrue(source.file.exists())
    }

    @Test
    fun exactArchiveSizeBoundaryIsRejected() {
        val source = source("one.bin", "deterministic payload".toByteArray(), 1_700_000_000_000L)
        val first = compress(listOf(source), MIB)
        val exactSize = File(first.archivePaths.single()).length()
        first.archivePaths.forEach { File(it).delete() }

        try {
            compress(listOf(source), exactSize)
            fail("Archive equal to the limit must not be accepted")
        } catch (e: SmartCompressionException) {
            assertTrue(e.message.orEmpty().contains("cannot fit below the selected limit"))
        }
    }

    @Test
    fun duplicateArchivePathsAreRejectedBeforeWriting() {
        val one = source("a.jpg", byteArrayOf(1), 1_700_000_000_000L)
        val two = source("b.jpg", byteArrayOf(2), 1_700_000_000_000L)
        try {
            SmartBatchCompressor.compress(
                listOf(one.entry.copy(id = "one", nameInArchive = "same.jpg"), two.entry.copy(id = "two", nameInArchive = "same.jpg")),
                MIB, null, ::target
            )
            fail("Duplicate paths must be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("Duplicate archive path"))
        }
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationDoesNotModifySourceOrPublishPartialArchive() {
        val source = source("long.bin", Random(8).nextBytes(400_000), 1_700_000_000_000L)
        var checks = 0
        try {
            SmartBatchCompressor.compress(
                listOf(source.entry), 2 * MIB, null, ::target,
                isCancelled = { ++checks > 1 }
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation during preflight occurs before a temporary archive is created.
        }
        assertTrue(source.file.exists())
        assertTrue(source.file.readBytes().contentEquals(source.bytes))
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun detectsSourceMetadataChangeAfterPreflight() {
        val source = source("changing.bin", Random(3).nextBytes(100_000), 1_700_000_000_000L)
        var metadataReads = 0
        val changing = source.entry.copy(readSourceMetadata = {
            metadataReads++
            if (metadataReads >= 3) source.file.length() to source.file.lastModified() + 2_000
            else source.file.length() to source.file.lastModified()
        })

        try {
            SmartBatchCompressor.compress(listOf(changing), MIB, null, ::target)
            fail("Expected a changed source to abort")
        } catch (e: SmartCompressionException) {
            assertTrue(e.message.orEmpty().contains("changed"))
        }
        assertTrue(source.file.exists())
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun keepsEarlierVerifiedArchiveWhenALaterSourceCannotFit() {
        val older = source("first.jpg", Random(10).nextBytes(100_000), 1_700_000_000_000L)
        val newer = source("later.bin", Random(11).nextBytes(1_300_000), 1_699_000_000_000L)
        try {
            compress(listOf(older, newer), MIB)
            fail("Expected the oversized source to stop the operation")
        } catch (e: SmartCompressionException) {
            assertEquals(1, e.completedArchives.size)
            assertTrue(e.message.orEmpty().contains("previously verified archive"))
        }
        val completed = File(outputs, "archive_001.zip")
        assertTrue(completed.exists())
        assertTrue(completed.length() < MIB)
        assertTrue(older.file.exists() && newer.file.exists())
    }

    @Test
    fun preservesNestedPathsDuplicateBasenamesAndEmptyDirectories() {
        val one = source("same.jpg", Random(1).nextBytes(1_000), 1_700_000_000_000L, "one-source.jpg")
        val two = source("same.jpg", Random(2).nextBytes(2_000), 1_699_000_000_000L, "two-source.jpg")
        val emptyDirectoryTime = 1_698_000_000_000L
        val entries = listOf(
            one.entry.copy(id = "one", nameInArchive = "Album/Day A/same.jpg"),
            two.entry.copy(id = "two", nameInArchive = "Album/Day B/same.jpg"),
            SmartArchiveEntry("empty-dir", "Album/Empty", 0, emptyDirectoryTime, isDirectory = true)
        )

        val result = SmartBatchCompressor.compress(entries, MIB, null, ::target)
        val names = mutableListOf<String>()
        ZipInputStream(FileInputStream(result.archivePaths.single())).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                names.add(next.fileName)
            }
        }
        assertEquals(setOf("Album/Day A/same.jpg", "Album/Day B/same.jpg", "Album/Empty/"), names.toSet())
        assertEquals(3, names.size)
    }

    @Test
    fun detectsSameSizeContentChangeEvenWhenMetadataStaysConstant() {
        val source = source("changing.bin", ByteArray(200_000) { 1 }, 1_700_000_000_000L)
        var streamCount = 0
        val changed = source.entry.copy(openSource = {
            streamCount++
            ByteArrayInputStream(ByteArray(200_000) { if (streamCount == 1) 1 else 2 })
        })

        try {
            SmartBatchCompressor.compress(listOf(changed), MIB, null, ::target)
            fail("Expected a changed source hash to abort")
        } catch (e: SmartCompressionException) {
            assertTrue(e.message.orEmpty().contains("changed during compression"))
        }
        assertTrue(source.file.exists())
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    private data class Source(val entryName: String, val bytes: ByteArray, val modifiedMillis: Long, val file: File) {
        val entry: SmartArchiveEntry
            get() = SmartArchiveEntry(
                id = file.canonicalPath,
                nameInArchive = entryName,
                sizeBytes = file.length(),
                modifiedMillis = file.lastModified(),
                openSource = { FileInputStream(file) },
                readSourceMetadata = { file.length() to file.lastModified() }
            )
    }

    private fun source(name: String, bytes: ByteArray, modified: Long, diskName: String = name): Source {
        val file = File(inputs, diskName)
        file.writeBytes(bytes)
        file.setLastModified(modified)
        return Source(name, bytes, modified, file)
    }

    private fun compress(sources: List<Source>, limit: Long): SmartCompressionResult =
        SmartBatchCompressor.compress(sources.map { it.entry }, limit, null, ::target)

    private fun target(number: Int): SmartArchiveTarget {
        val finalFile = File(outputs, "archive_${number.toString().padStart(3, '0')}.zip")
        val temporaryFile = File(outputs, ".${finalFile.name}.partial")
        return object : SmartArchiveTarget {
            override val finalName: String = finalFile.absolutePath
            override val temporaryName: String = temporaryFile.absolutePath
            override fun openOutput(): OutputStream = FileOutputStream(temporaryFile)
            override fun openInput(): InputStream = FileInputStream(temporaryFile)
            override fun actualSizeBytes() = temporaryFile.length()
            override fun publish() {
                check(!finalFile.exists())
                Files.move(temporaryFile.toPath(), finalFile.toPath())
            }
            override fun deleteTemporary(): Boolean = !temporaryFile.exists() || temporaryFile.delete()
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
