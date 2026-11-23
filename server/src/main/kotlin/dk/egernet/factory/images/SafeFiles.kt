package dk.egernet.factory.images

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** A directory the server was to write into is a symlink or another non-directory, or lies outside its root. */
class UnsafeDirectoryException(message: String) : IOException(message)

/**
 * File operations that never follow a symlink planted where the server writes (docs/ARCHITECTURE.md §6 "Generated
 * images"): a repository may contain `.agern-run` (or any target file name) as a symlink to elsewhere.
 */
internal object SafeFiles {
    /**
     * Makes sure [dir] is a real directory (not a symlink), creating it if absent. Its parent must already have been
     * checked the same way. Throws [UnsafeDirectoryException] otherwise.
     */
    fun plainDirectory(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir)
            } catch (_: FileAlreadyExistsException) {
                // Created meanwhile; checked below.
            }
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw UnsafeDirectoryException("$dir is not a plain directory")
    }

    /** [sub] (relative names) below [root], each a plain directory, the last one's real path inside [root]'s. Returns it. */
    fun plainDirectories(root: Path, vararg sub: String): Path {
        val realRoot = root.toRealPath()
        var dir = root
        for (s in sub) {
            dir = dir.resolve(s)
            plainDirectory(dir)
        }
        if (!dir.toRealPath().startsWith(realRoot)) throw UnsafeDirectoryException("$dir is outside $root")
        return dir
    }

    /**
     * Writes [bytes] to [target] without following a symlink there: an existing symlink or file is removed first and the
     * file is created anew. A directory at [target] is an [UnsafeDirectoryException].
     */
    fun write(target: Path, bytes: ByteArray) {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw UnsafeDirectoryException("$target is a directory")
        Files.deleteIfExists(target) // removes a symlink itself, never its target
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
    }

    /** Deletes [path] and everything below it without following symlinks (a symlink is removed, not its target). */
    fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (exc !is NoSuchFileException) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: NoSuchFileException) {
        }
    }
}
