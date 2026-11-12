package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentStorageSourceTest {
    @Test
    fun classifiesFilesWithinConfiguredStorageRoots() {
        assertEquals(
            RecentStorageSource.INTERNAL,
            RecentStorageSourceResolver.classify("/storage/emulated/0/Pictures/photo.jpg", "/storage/emulated/0", "/storage/ABCD-1234")
        )
        assertEquals(
            RecentStorageSource.USB,
            RecentStorageSourceResolver.classify("/storage/ABCD-1234/DCIM/Camera/clip.mp4", "/storage/emulated/0", "/storage/ABCD-1234/")
        )
    }

    @Test
    fun doesNotTreatPrefixLookalikesAsFilesInsideAStorageRoot() {
        assertEquals(
            RecentStorageSource.OTHER,
            RecentStorageSourceResolver.classify("/storage/emulated/00/Pictures/photo.jpg", "/storage/emulated/0", "/storage/ABCD-1234")
        )
        assertEquals(
            RecentStorageSource.OTHER,
            RecentStorageSourceResolver.classify("/storage/ABCD-12340/file.txt", "/storage/emulated/0", "/storage/ABCD-1234")
        )
    }

    @Test
    fun emptyRootsDoNotClassifyPathsAsConfiguredSources() {
        assertEquals(RecentStorageSource.OTHER, RecentStorageSourceResolver.classify("/storage/file.txt", "", ""))
    }
}
