package fish.nexa

import fish.nexa.crypto.Bytes
import fish.nexa.crypto.DecryptError
import fish.nexa.crypto.FileCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileCompletenessTest {
    // Parts going straight to storage are cut by bytes, across chunks and the
    // header, as the web cuts them; joined, they must be the whole file exactly.
    @Test fun storagePartsCutByBytesJoinIntoTheWholeFile() {
        val plain = ByteArray(2 * FileCrypto.CHUNK + 4321) { (it * 7 + 3).toByte() }
        val key = Bytes.random(32)
        val iv = FileCrypto.newBaseIv()
        val file = File.createTempFile("nexa-parts-", ".bin")
        try {
            file.writeBytes(plain)
            val whole = FileCrypto.encryptBytes(plain, key, iv)
            for (part in listOf(whole.size.toLong(), (whole.size + 2L) / 3, FileCrypto.FRAME_LEN - 1L)) {
                val out = ByteArrayOutputStream()
                var from = 0L
                RandomAccessFile(file, "r").use { raf ->
                    while (from < whole.size) {
                        val to = minOf(whole.size.toLong(), from + part)
                        val (data, start) = fish.nexa.data.Uploads.encryptBytes(raf, plain.size.toLong(), key, iv, from, to)
                        out.write(data, start, (to - from).toInt())
                        from = to
                    }
                }
                assertArrayEquals("parts of $part", whole, out.toByteArray())
            }
        } finally {
            file.delete()
        }
    }

    @Test fun preparedPiecesMatchTheWholeFileIncludingTheShortLastChunk() {
        val plain = ByteArray(2 * FileCrypto.CHUNK + 17) { (it * 31).toByte() }
        val key = Bytes.random(32)
        val iv = FileCrypto.newBaseIv()
        val file = File.createTempFile("nexa-transfer-", ".bin")
        try {
            file.writeBytes(plain)
            RandomAccessFile(file, "r").use { source ->
                val first = FileCrypto.encryptChunks(source, plain.size.toLong(), key, iv, 0, 2)
                val last = FileCrypto.encryptChunks(source, plain.size.toLong(), key, iv, 2, 1)
                assertArrayEquals(FileCrypto.encryptBytes(plain, key, iv), first + last)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun aWholeAuthenticatedChunkDoesNotProveTheFileIsComplete() {
        val plain = ByteArray(FileCrypto.CHUNK + 1)
        val key = Bytes.random(32)
        val encrypted = FileCrypto.encryptBytes(plain, key)
        val prefix = encrypted.copyOf(FileCrypto.encryptedSize(FileCrypto.CHUNK.toLong()).toInt())
        assertThrows(DecryptError::class.java) {
            FileCrypto.decryptStream(prefix.inputStream(), key, ByteArrayOutputStream(), expectedSize = plain.size.toLong())
        }
        val out = ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), FileCrypto.decryptStream(encrypted.inputStream(), key, out, expectedSize = plain.size.toLong()))
    }
}
