package fish.nexa

import fish.nexa.crypto.Aad
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.Conv
import fish.nexa.crypto.Envelope
import fish.nexa.crypto.FileCrypto
import fish.nexa.crypto.Fingerprint
import fish.nexa.crypto.Identity
import fish.nexa.crypto.Kdf
import fish.nexa.crypto.Keys
import fish.nexa.crypto.MessageCrypto
import fish.nexa.crypto.NoteCrypto
import fish.nexa.crypto.SettingsCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer

/**
 * Ciphertexts the web client made (web/src/crypto, through vite-node), which
 * this client must open and whose derived keys it must reproduce exactly: a
 * message written on a phone has to read in a browser and the other way round.
 */
class CryptoVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.getResource("/web-crypto-vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private fun hex(k: String) = unhex(s(k))
    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun argon2idMatchesTheWeb() {
        // argon2kt is native and does not run here; BouncyCastle's argon2id checks the
        // parameters and the NFKC + UTF-8 password handling the app uses.
        val pw = Bytes.utf8(Normalizer.normalize("correct horse battery staple ünïcödé ｆｕｌｌ", Normalizer.Form.NFKC))
        val gen = Argon2BytesGenerator()
        gen.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(hex("salt")).withIterations(Kdf.DEFAULT.t).withMemoryAsKB(Kdf.DEFAULT.m).withParallelism(Kdf.DEFAULT.p).build())
        val out = ByteArray(32)
        gen.generateBytes(pw, out)
        assertArrayEquals(hex("master"), out)
    }

    @Test fun keysDerive() {
        val master = hex("master")
        assertArrayEquals(hex("authKey"), Kdf.authKey(master))
        assertArrayEquals(hex("encKey"), Kdf.encKey(master))
        assertArrayEquals(hex("settingsKey"), Kdf.settingsKey(hex("priv")))
        assertArrayEquals(hex("pub"), Identity.publicKeyOf(hex("priv")))
        assertEquals(s("fingerprint"), Fingerprint.of(hex("pub")))
        assertArrayEquals(hex("chanKey"), Conv.channelKey(hex("root"), "99723385805733891"))
        assertArrayEquals(hex("voiceKey"), Conv.voiceFrameKey(hex("root"), "99723385805733891"))
        assertArrayEquals(hex("keyCommitment"), Conv.keyCommitment(hex("root"), "99723385805733891"))
        val rec = v["recovery"]!!.jsonObject
        val bytes = Kdf.recoveryKeyBytes("0123ABCD-4567EF01-89abcdef-01234567-89ABCDEF-01234567-89ABCDEF-0123 4567")
        assertArrayEquals(unhex(rec["bytes"]!!.jsonPrimitive.content), bytes)
        assertArrayEquals(unhex(rec["auth"]!!.jsonPrimitive.content), Kdf.recoveryAuthKey(bytes))
        assertArrayEquals(unhex(rec["enc"]!!.jsonPrimitive.content), Kdf.recoveryEncKey(bytes))
    }

    @Test fun boxesOpen() {
        assertArrayEquals(hex("priv"), Keys.unwrapPrivateKey(Bytes.unb64(s("wrapped")), hex("encKey")))
        assertEquals("sealed secret", Bytes.str(Keys.open(hex("priv"), hex("pub"), Bytes.unb64(s("sealed")))))
        // and the other way: what this client seals opens again
        val again = Keys.seal(hex("pub"), Bytes.utf8("round trip"))
        assertEquals("round trip", Bytes.str(Keys.open(hex("priv"), hex("pub"), again)))
    }

    @Test fun messageOpens() {
        val env = Envelope.from(v["envelope"])!!
        val a = v["aad"]!!.jsonObject
        val aad = Aad(a["conv"]!!.jsonPrimitive.content, a["epoch"]!!.jsonPrimitive.content.toInt(), a["channelId"]!!.jsonPrimitive.content, a["authorId"]!!.jsonPrimitive.content)
        val p = MessageCrypto.decrypt(hex("chanKey"), env, aad)
        assertEquals("hello **world** 👋🏽", p.t)
        val att = p.att!!.single()
        assertEquals("123", att.blobId)
        assertEquals(42L, att.size)
        assertEquals(true, att.spoiler)
        assertEquals("124", att.preview!!.blobId)
        val embed = p.embeds!!.single()
        assertEquals("https://example.com", embed["url"]!!.jsonPrimitive.content)
        assertNull(embed["bogus"])
        assertEquals("bluesky", embed["post"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals(48, p.poll!!.hours)
        assertTrue(p.poll!!.multiselect)
        assertEquals("😀", p.poll!!.answers[0].emoji!!.name)
        // a wrong author cannot claim it
        try {
            MessageCrypto.decrypt(hex("chanKey"), env, aad.copy(authorId = "556"))
            throw AssertionError("decrypted under the wrong author")
        } catch (_: fish.nexa.crypto.DecryptError) {
        }
        // what this client seals, it opens
        val mine = MessageCrypto.encrypt(hex("chanKey"), aad, p)
        assertEquals(p, MessageCrypto.decrypt(hex("chanKey"), mine, aad))
    }

    @Test fun filesOpen() {
        val empty = FileCrypto.decryptBytes(Bytes.unb64(s("emptyFile")), hex("emptyKey"))
        assertEquals(0, empty.size)
        // the multi-chunk file is too big to keep in the repository; checked when present
        val big = System.getenv("NEXA_WEB_ECF")?.let { File(it) }?.takeIf { it.exists() } ?: return
        val sink = ByteArrayOutputStream()
        val n = FileCrypto.decryptStream(big.inputStream().buffered(), hex("fileKey"), sink)
        assertEquals(v["fileSize"]!!.jsonPrimitive.content.toLong(), n)
        assertEquals(s("fileSha"), Bytes.hex(Bytes.sha256(sink.toByteArray())))
        assertEquals(n, FileCrypto.plainSizeOf(big.length()))
        assertEquals(big.length(), FileCrypto.encryptedSize(n))
    }

    @Test fun settingsAndNotesOpen() {
        val doc = SettingsCrypto.decrypt(hex("settingsKey"), s("settings"))!!.jsonObject
        assertEquals("dark", doc["theme"]!!.jsonPrimitive.content)
        val head = NoteCrypto.open(hex("noteKey"), NoteCrypto.Part.HEAD, s("noteHead")).jsonObject
        assertEquals("T", head["title"]!!.jsonPrimitive.content)
        assertArrayEquals(hex("noteKey"), NoteCrypto.openKey(Identity(hex("pub"), hex("priv")), s("noteKeySealed")))
        val l = v["link"]!!.jsonObject
        val secret = NoteCrypto.LinkSecret(l["token"]!!.jsonPrimitive.content, l["key"]!!.jsonPrimitive.content)
        assertArrayEquals(hex("noteKey"), NoteCrypto.unwrapKeyFromLink(secret, s("linkWrapped")))
        assertEquals(s("linkHash"), NoteCrypto.tokenHash(secret.token))
        assertEquals(secret, NoteCrypto.parseLink("https://nexa.fish" + NoteCrypto.linkPath(secret), "https://nexa.fish"))
    }
}
