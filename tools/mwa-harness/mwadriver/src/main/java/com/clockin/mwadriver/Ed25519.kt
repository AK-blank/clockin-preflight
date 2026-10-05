package com.clockin.mwadriver

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 verification, via BouncyCastle's low-level API.
 *
 * The AOSP API-35 image has no software Ed25519 JCA provider (see the wallet-side note), so we
 * use BouncyCastle primitives directly rather than `Signature.getInstance("Ed25519")`.
 */
object Ed25519 {

    /** Verify an Ed25519 signature over [message] for a raw 32-byte Solana public key. */
    fun verify(rawPubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(rawPubkey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}
