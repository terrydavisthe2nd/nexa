package fish.nexa.crypto

import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * Identity keys, private-key wrapping and sealed boxes (web/src/crypto/keys.ts).
 *
 * An identity is a long-lived x25519 pair. Its private half is wrapped with the
 * password-derived key before the server ever sees it; conversation root keys
 * reach members as sealed boxes to their public key:
 * ephemeral public key (32) | iv (12) | AES-256-GCM ciphertext and tag, with the
 * ephemeral public key as additional data.
 */
class Identity(val publicKey: ByteArray, val privateKey: ByteArray) {
    companion object {
        fun generate(): Identity {
            val priv = Bytes.random(32)
            return Identity(publicKeyOf(priv), priv)
        }

        fun publicKeyOf(priv: ByteArray): ByteArray {
            val pub = ByteArray(32)
            X25519.generatePublicKey(priv, 0, pub, 0)
            return pub
        }
    }
}

object Keys {
    private val AAD_PRIVKEY = Bytes.utf8("privkey/v1")
    private val INFO_SEAL = Bytes.utf8("evilion-chat/seal/v1")

    fun wrapPrivateKey(priv: ByteArray, encKey: ByteArray): ByteArray {
        val iv = Bytes.random(Bytes.IV_LEN)
        return Bytes.concat(iv, Bytes.gcmEncrypt(encKey, iv, priv, AAD_PRIVKEY))
    }

    fun unwrapPrivateKey(blob: ByteArray, encKey: ByteArray): ByteArray {
        if (blob.size < Bytes.IV_LEN + Bytes.TAG_LEN) throw DecryptError("wrapped private key is too short")
        try {
            return Bytes.gcmDecrypt(encKey, blob.copyOfRange(0, Bytes.IV_LEN), blob, AAD_PRIVKEY, Bytes.IV_LEN, blob.size - Bytes.IV_LEN)
        } catch (_: DecryptError) {
            throw DecryptError("wrapped private key failed to decrypt")
        }
    }

    /** x25519 that refuses a low-order point, as noble's getSharedSecret does. */
    private fun shared(priv: ByteArray, pub: ByteArray): ByteArray {
        if (pub.size != 32) throw DecryptError("not an x25519 key")
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw DecryptError("low-order point")
        return out
    }

    private fun sealKey(ephPub: ByteArray, recipientPub: ByteArray, shared: ByteArray) =
        Bytes.hkdf(shared, ByteArray(0), Bytes.concat(INFO_SEAL, ephPub, recipientPub), 32)

    fun seal(recipientPub: ByteArray, data: ByteArray): ByteArray {
        val eph = Identity.generate()
        val key = sealKey(eph.publicKey, recipientPub, shared(eph.privateKey, recipientPub))
        val iv = Bytes.random(Bytes.IV_LEN)
        return Bytes.concat(eph.publicKey, iv, Bytes.gcmEncrypt(key, iv, data, eph.publicKey))
    }

    fun open(recipientPriv: ByteArray, recipientPub: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 32 + Bytes.IV_LEN + Bytes.TAG_LEN) throw DecryptError("sealed box is too short")
        val ephPub = blob.copyOfRange(0, 32)
        val iv = blob.copyOfRange(32, 32 + Bytes.IV_LEN)
        try {
            val key = sealKey(ephPub, recipientPub, shared(recipientPriv, ephPub))
            return Bytes.gcmDecrypt(key, iv, blob, ephPub, 32 + Bytes.IV_LEN, blob.size - 32 - Bytes.IV_LEN)
        } catch (_: DecryptError) {
            throw DecryptError("sealed box failed to decrypt")
        }
    }
}

/**
 * Conversation key material (web/src/crypto/conv.ts): a root key per epoch,
 * and per-channel message and call keys derived from it.
 */
object Conv {
    fun newRootKey(): ByteArray = Bytes.random(32)

    fun channelKey(root: ByteArray, channelId: String): ByteArray =
        Bytes.hkdf(root, ByteArray(0), Bytes.utf8("chan:$channelId"), 32)

    /** The key call frames are sealed with; derived apart from the message key. */
    fun voiceFrameKey(root: ByteArray, channelId: String): ByteArray =
        Bytes.hkdf(root, ByteArray(0), Bytes.utf8("voice:$channelId"), 32)

    /**
     * A public, one-way fingerprint of a conversation's key, which the epoch's
     * maker gives the server and every member checks the key they were handed
     * against (web crypto/conv.ts keyCommitment).
     */
    fun keyCommitment(root: ByteArray, conv: String): ByteArray =
        Bytes.hkdf(root, ByteArray(0), Bytes.utf8("commit:$conv"), 32)
}

/** Conversation root keys held in memory, by conversation and epoch. */
class KeyRing {
    private val rings = HashMap<String, java.util.TreeMap<Int, ByteArray>>()

    @Synchronized fun set(conv: String, epoch: Int, root: ByteArray) {
        rings.getOrPut(conv) { java.util.TreeMap() }[epoch] = root
    }

    @Synchronized fun get(conv: String, epoch: Int): ByteArray? = rings[conv]?.get(epoch)

    @Synchronized fun latest(conv: String): Pair<Int, ByteArray>? = rings[conv]?.lastEntry()?.let { it.key to it.value }

    @Synchronized fun has(conv: String): Boolean = (rings[conv]?.size ?: 0) > 0

    @Synchronized fun convs(): List<String> = rings.keys.toList()

    @Synchronized fun epochs(conv: String): List<Int> = rings[conv]?.keys?.toList() ?: emptyList()

    @Synchronized fun all(): Map<String, Map<Int, ByteArray>> = rings.mapValues { HashMap(it.value) }

    @Synchronized fun clear() = rings.clear()
}
