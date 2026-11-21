package com.studentstorefront.service

import java.io.File

/** Recognises the image formats phones and browsers actually send, by their leading bytes. */
object ImageSignature {
    fun isSupportedImage(file: File): Boolean {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        return read >= 12 && matches(head)
    }

    fun matches(head: ByteArray): Boolean {
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return at(0, 0xFF, 0xD8, 0xFF) ||                                  // JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||           // PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") ||                      // GIF
            (ascii(0, "RIFF") && ascii(8, "WEBP")) ||                        // WebP
            (ascii(4, "ftyp") && listOf("heic", "heix", "mif1", "msf1", "avif", "hevc").any { ascii(8, it) }) // HEIC/AVIF
    }
}
