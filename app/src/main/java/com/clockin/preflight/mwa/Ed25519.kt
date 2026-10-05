package com.clockin.preflight.mwa

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 verification of what the wallet returned, so the app can prove the signature is real
 * instead of just displaying bytes it was handed.
 *
 * BouncyCastle's low-level API is used deliberately: the AOSP API-35 image (and the emulator image
 * we demo on) ships **no software Ed25519 JCA provider** — `Signature.getInstance("Ed25519")`
 * resolves to AndroidKeyStore and throws `IllegalStateException: Not initialized`. `bcprov-jdk18on`
 * is already on the app's classpath.
 */
object Ed25519 {

    /** Verify an Ed25519 signature over [message] for a raw 32-byte Solana public key. */
    fun verify(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (rawPublicKey.size != LegacyTransaction.PUBLIC_KEY_LENGTH) return false
        if (signature.size != LegacyTransaction.SIGNATURE_LENGTH) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(rawPublicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }
}
