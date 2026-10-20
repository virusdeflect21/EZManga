package app.ezmanga.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun createTestCbz(file: File, pageNames: List<String>) {
    file.parentFile?.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("ComicInfo.xml"))
        zip.write("<ComicInfo><Title>Test</Title></ComicInfo>".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("__MACOSX/._001.jpg"))
        zip.write(byteArrayOf(0))
        zip.closeEntry()

        pageNames.forEachIndexed { index, name ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(byteArrayOf(index.toByte(), index.toByte(), index.toByte(), index.toByte()))
            zip.closeEntry()
        }
    }
}
