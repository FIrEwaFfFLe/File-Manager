package org.fossify.filemanager.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZipPathSafetyTest {
    @Test
    fun allowsEntriesInsideExtractionDirectory() {
        val root = File("build/test-zip-output/root").absolutePath
        assertTrue(ZipPathSafety.isInsideDirectory(root, "$root/folder/file.txt"))
    }

    @Test
    fun rejectsParentTraversal() {
        val root = File("build/test-zip-output/root").absolutePath
        assertFalse(ZipPathSafety.isInsideDirectory(root, "$root/../escape.txt"))
    }

    @Test
    fun rejectsSiblingPathsThatOnlyShareTheRootPrefix() {
        val root = File("build/test-zip-output/restore").absolutePath
        assertFalse(ZipPathSafety.isInsideDirectory(root, "$root-evil/file.txt"))
    }
}
