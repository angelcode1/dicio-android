package org.stypox.dicio.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source

class BinaryFileDownloaderTest : StringSpec({
    "download marker is invalid when target file is missing" {
        val root = Files.createTempDirectory("dicio-download-test").toFile()
        try {
            val target = root.resolve("model.bin")
            val item = FileToDownload("https://example.test/model.bin", target)
            item.lastDownloadedUrlFile.writeText(item.url)

            item.needsToBeDownloaded() shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }

    "download marker is valid only when target and URL both match" {
        val root = Files.createTempDirectory("dicio-download-test").toFile()
        try {
            val target = root.resolve("model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val item = FileToDownload("https://example.test/model.bin", target)
            item.lastDownloadedUrlFile.writeText(item.url)

            item.needsToBeDownloaded() shouldBe false
            item.lastDownloadedUrlFile.writeText("https://example.test/other.bin")
            item.needsToBeDownloaded() shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }

    "non-success HTTP response is rejected" {
        val response = response(code = 404, message = "Not Found", body = "missing")
        try {
            shouldThrow<IOException> {
                runBlocking {
                    downloadBinaryFile(response, ByteArrayOutputStream()) { _, _ -> }
                }
            }
        } finally {
            response.close()
        }
    }

    "truncated response with content length is rejected" {
        val response = response(
            code = 200,
            message = "OK",
            body = "short",
            declaredLength = 100,
        )
        try {
            shouldThrow<IOException> {
                runBlocking {
                    downloadBinaryFile(response, ByteArrayOutputStream()) { _, _ -> }
                }
            }
        } finally {
            response.close()
        }
    }

    "partial download installs complete file" {
        val root = Files.createTempDirectory("dicio-download-test").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val target = root.resolve("files/model.bin")
            val response = response(code = 200, message = "OK", body = "complete model")

            runBlocking {
                downloadBinaryFileWithPartial(response, target, cache) { _, _ -> }
            }

            target.readText() shouldBe "complete model"
            cache.listFiles()?.filter { it.name.endsWith(".part") }?.size shouldBe 0
        } finally {
            root.deleteRecursively()
        }
    }

    "orphan partial cleanup preserves unrelated cache files" {
        val root = Files.createTempDirectory("dicio-download-test").toFile()
        try {
            root.resolve("model.bin123.part").writeText("partial")
            root.resolve("other.tmp").writeText("keep")

            deletePartialFiles(root)

            root.resolve("model.bin123.part").exists() shouldBe false
            root.resolve("other.tmp").exists() shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }
}) {
    companion object {
        private fun response(
            code: Int,
            message: String,
            body: String,
            declaredLength: Long? = null,
        ): Response {
            val responseBody = if (declaredLength == null) {
                body.toResponseBody()
            } else {
                object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = declaredLength
                    override fun source(): BufferedSource = body.byteInputStream().source().buffer()
                }
            }
            return Response.Builder()
                .request(Request.Builder().url("https://example.test/model.bin").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(responseBody)
                .build()
        }
    }
}
