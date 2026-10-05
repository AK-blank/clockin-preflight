package com.clockin.preflight.mwa

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The app verifies what the wallet returns instead of trusting it, so the verifier needs its own
 * test: sign a real transaction message with a real Ed25519 key and check that tampering anywhere
 * (message, signature, key size) is rejected.
 *
 * BouncyCastle is used on both sides because the AOSP API-35 image has no software Ed25519 JCA
 * provider (see tools/MWA-RUNTIME-REPORT.md, gotcha 4). On the JVM these are ordinary classes.
 */
class Ed25519Test {

    private val keyPair = Ed25519KeyPairGenerator()
        .apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        .generateKeyPair()

    private val privateKey = keyPair.private as Ed25519PrivateKeyParameters
    private val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded

    private fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    @Test
    fun `verifies a signature over the exact message a wallet would sign`() {
        val tx = LegacyTransaction.transfer(
            from = publicKey,
            to = ByteArray(32) { (it + 9).toByte() },
            lamports = 1_000_000L,
        )
        val message = LegacyTransaction.message(tx)
        val signature = sign(message)

        // the wallet's answer is "request with slot 0 filled in"
        val response = LegacyTransaction.withSignature(tx, signature)

        assertTrue(Ed25519.verify(publicKey, LegacyTransaction.message(response), signature))
        assertTrue(Ed25519.verify(publicKey, message, LegacyTransaction.signature(response)))
        assertTrue(LegacyTransaction.signatureSlotMatches(tx, response))
    }

    @Test
    fun `rejects a tampered message, a tampered signature and malformed lengths`() {
        val message = LegacyTransaction.message(
            LegacyTransaction.transfer(publicKey, ByteArray(32) { 1 }, 42L),
        )
        val signature = sign(message)

        assertTrue(Ed25519.verify(publicKey, message, signature))

        val tamperedMessage = message.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(Ed25519.verify(publicKey, tamperedMessage, signature))

        val tamperedSignature = signature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Ed25519.verify(publicKey, message, tamperedSignature))

        val otherKey = Ed25519KeyPairGenerator()
            .apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
            .generateKeyPair()
        assertFalse(
            Ed25519.verify((otherKey.public as Ed25519PublicKeyParameters).encoded, message, signature),
        )

        assertFalse(Ed25519.verify(publicKey.copyOf(31), message, signature))
        assertFalse(Ed25519.verify(publicKey, message, signature.copyOf(63)))
        assertFalse(Ed25519.verify(publicKey, message, ByteArray(0)))
    }
}
