/*
 * Taken from /e/OS Assistant
 *
 * Copyright (C) 2024 MURENA SAS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.stypox.dicio.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.stypox.dicio.ui.util.Progress

private const val CHUNK_SIZE = 1024 * 256

suspend fun extractZip(
    sourceZip: File,
    destinationDirectory: File,
    progressCallback: (Progress) -> Unit,
) = withContext(Dispatchers.IO) {
    ZipFile(sourceZip).use { zipFile ->
        var currentCount = 0
        var totalCount = 0
        for (entry in zipFile.entries()) {
            if (!entry.isDirectory) totalCount += 1
        }

        for (entry in zipFile.entries()) {
            val destinationFile = getDestinationFile(destinationDirectory, entry.name)

            if (entry.isDirectory) {
                if (!destinationFile.exists() && !destinationFile.mkdirs()) {
                    throw IOException("mkdirs failed: $destinationFile")
                }
                continue
            }

            // Valid ZIPs do not have to contain explicit directory entries. Always ensure a file's
            // parent exists before opening its output stream.
            destinationFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("mkdirs failed: $parent")
                }
            }

            zipFile.getInputStream(entry).use { inputStream ->
                BufferedOutputStream(FileOutputStream(destinationFile)).use { outputStream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var currentBytes = 0L
                    progressCallback(Progress(currentCount, totalCount, 0, entry.size))

                    while (true) {
                        val length = inputStream.read(buffer)
                        if (length <= 0) break
                        yield()
                        outputStream.write(buffer, 0, length)
                        currentBytes += length
                        progressCallback(
                            Progress(currentCount, totalCount, currentBytes, entry.size)
                        )
                    }
                    outputStream.flush()
                }
            }

            currentCount += 1
        }
    }
}

@Throws(IOException::class)
fun getDestinationFile(destinationDirectory: File, entryName: String): File {
    val filePath = entryName.substring(entryName.indexOf('/') + 1)
    val destinationFile = File(destinationDirectory, filePath)
    if (destinationDirectory.canonicalPath != destinationFile.canonicalPath &&
        !destinationFile.canonicalPath.startsWith(destinationDirectory.canonicalPath + File.separator)
    ) {
        throw IOException("Entry is outside of the target dir: $entryName")
    }
    return destinationFile
}
