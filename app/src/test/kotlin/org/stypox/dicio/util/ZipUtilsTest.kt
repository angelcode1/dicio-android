package org.stypox.dicio.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

class ZipUtilsTest : StringSpec({
    "extracts nested file when ZIP has no explicit directory entries" {
        val root = Files.createTempDirectory("dicio-zip-test").toFile()
        try {
            val archive = root.resolve("model.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("model/subdir/file.txt"))
                zip.write("payload".toByteArray())
                zip.closeEntry()
            }
            val destination = root.resolve("destination").apply { mkdirs() }

            runBlocking {
                extractZip(archive, destination) { }
            }

            destination.resolve("subdir/file.txt").readText() shouldBe "payload"
        } finally {
            root.deleteRecursively()
        }
    }

    "rejects ZIP slip paths" {
        val destination = Files.createTempDirectory("dicio-zip-slip-test").toFile()
        try {
            shouldThrow<IOException> {
                getDestinationFile(destination, "model/../../outside.txt")
            }
        } finally {
            destination.deleteRecursively()
        }
    }
})
