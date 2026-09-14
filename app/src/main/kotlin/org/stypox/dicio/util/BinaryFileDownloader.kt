package org.stypox.dicio.util

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.stypox.dicio.ui.util.Progress

private const val CHUNK_SIZE = 1024 * 256
private const val DOT_PART_SUFFIX = ".part"
private const val LAST_DOWNLOAD_URL_CHECK = ".url.txt"

data class FileToDownload(
    val url: String,
    val file: File,
    val lastDownloadedUrlFile: File = File(file.parentFile, file.name + LAST_DOWNLOAD_URL_CHECK)
) {
    fun needsToBeDownloaded(): Boolean {
        if (!file.isFile || file.length() <= 0L) return true
        return try {
            lastDownloadedUrlFile.readText() != url
        } catch (_: IOException) {
            true
        }
    }
}

suspend fun downloadBinaryFilesWithPartial(
    urlsFiles: List<FileToDownload>,
    httpClient: OkHttpClient,
    cacheDir: File,
    progressCallback: (Progress) -> Unit,
) {
    progressCallback(Progress.UNKNOWN)
    val filesNeedingDownload = urlsFiles.filter(FileToDownload::needsToBeDownloaded)

    for ((i, fileToDownload) in filesNeedingDownload.withIndex()) {
        yield()
        progressCallback(Progress(i, filesNeedingDownload.size, 0, 0))

        val response = httpClient.getResponse(fileToDownload.url)
        downloadBinaryFileWithPartial(
            response = response,
            file = fileToDownload.file,
            cacheDir = cacheDir,
        ) { currentBytes, totalBytes ->
            progressCallback(Progress(i, filesNeedingDownload.size, currentBytes, totalBytes))
        }

        // The marker is written only after the target file has been completely and safely installed.
        withContext(Dispatchers.IO) {
            fileToDownload.lastDownloadedUrlFile.parentFile?.mkdirs()
            fileToDownload.lastDownloadedUrlFile.writeText(fileToDownload.url)
        }
    }

    progressCallback(Progress(filesNeedingDownload.size, filesNeedingDownload.size, 0, 0))
}

fun deletePartialFiles(cacheDir: File) {
    cacheDir.list { _, name -> name.endsWith(DOT_PART_SUFFIX) }
        ?.forEach { File(cacheDir, it).delete() }
}

@Throws(IOException::class)
suspend fun downloadBinaryFileWithPartial(
    response: Response,
    file: File,
    cacheDir: File,
    progressCallback: (currentBytes: Long, totalBytes: Long) -> Unit,
) {
    val partialFile = withContext(Dispatchers.IO) {
        File.createTempFile(file.name, DOT_PART_SUFFIX, cacheDir)
    }
    try {
        response.use { successfulResponse ->
            partialFile.outputStream().use { output ->
                downloadBinaryFile(successfulResponse, output, progressCallback)
            }
        }

        withContext(Dispatchers.IO) {
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create target directory $parent")
                }
            }
            try {
                Files.move(
                    partialFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    partialFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    } finally {
        withContext(Dispatchers.IO) { partialFile.delete() }
    }
}

@Throws(IOException::class)
suspend fun downloadBinaryFile(
    response: Response,
    outputStream: OutputStream,
    progressCallback: (currentBytes: Long, totalBytes: Long) -> Unit,
) {
    if (!response.isSuccessful) {
        throw IOException("HTTP ${response.code} ${response.message}")
    }
    val responseBody = response.body ?: throw IOException("Response doesn't contain a file")
    val totalBytes = responseBody.contentLength().takeIf { it >= 0L } ?: 0L

    progressCallback(0, totalBytes)
    BufferedInputStream(responseBody.byteStream()).use { input ->
        val dataBuffer = ByteArray(CHUNK_SIZE)
        var currentBytes = 0L
        while (true) {
            val readBytes = input.read(dataBuffer)
            if (readBytes == -1) break
            if (readBytes == 0) continue
            yield()
            currentBytes += readBytes.toLong()
            outputStream.write(dataBuffer, 0, readBytes)
            progressCallback(currentBytes, totalBytes)
        }
    }
}

fun OkHttpClient.getResponse(url: String): Response {
    val request = Request.Builder().url(url).build()
    val response = newCall(request).execute()
    if (!response.isSuccessful) {
        val code = response.code
        val message = response.message
        response.close()
        throw IOException("HTTP $code $message while downloading $url")
    }
    return response
}
