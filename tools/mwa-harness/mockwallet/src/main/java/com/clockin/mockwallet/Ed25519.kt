package com.clockin.mockwallet

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519, via BouncyCastle's low-level API.
 *
 * WHY NOT JCA: the AOSP API-35 `default` system image ships **no software Ed25519 provider**.
 * Probed on the clockin35 AVD, all three JCA routes fail:
 *   AndroidOpenSSL (Conscrypt) -> NoSuchAlgorithmException: no such algorithm: Ed25519
 *   BC                         -> NoSuchAlgorithmException: no such algorithm: Ed25519
 *   default (no provider)      -> resolves to AndroidKeyStore ->
 *                                 IllegalStateException: Not initialized
 * BouncyCastle's `org.bouncycastle.crypto` primitives are self-contained (no JCA provider
 * registration, no hardware keystore), so they work identically on any image.
 */
object Ed25519 {

    fun newKeyPair(): AsymmetricCipherKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        return generator.generateKeyPair()
    }

    /** Raw 32-byte Solana-style public key. */
    fun publicKeyRaw(pair: AsymmetricCipherKeyPair): ByteArray =
        (pair.public as Ed25519PublicKeyParameters).encoded

    fun sign(pair: AsymmetricCipherKeyPair, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, pair.private as Ed25519PrivateKeyParameters)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }
}
