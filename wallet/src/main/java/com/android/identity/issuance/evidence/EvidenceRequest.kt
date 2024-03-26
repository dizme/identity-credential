package com.android.identity.issuance.evidence

import com.android.identity.cbor.annotation.CborSerializable
import com.android.identity.cbor.Cbor

/**
 * A request for evidence by the issuer.
 */
@CborSerializable
sealed class EvidenceRequest {
    companion object
}
