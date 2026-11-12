package org.fossify.filemanager.helpers

enum class RecentStorageSource { INTERNAL, USB, OTHER }

object RecentStorageSourceResolver {
    fun classify(path: String, internalRoot: String, usbRoot: String): RecentStorageSource = when {
        isWithin(path, internalRoot) -> RecentStorageSource.INTERNAL
        isWithin(path, usbRoot) -> RecentStorageSource.USB
        else -> RecentStorageSource.OTHER
    }

    private fun isWithin(path: String, root: String): Boolean {
        val normalizedRoot = root.trimEnd('/')
        return normalizedRoot.isNotEmpty() && (path == normalizedRoot || path.startsWith("$normalizedRoot/"))
    }
}
