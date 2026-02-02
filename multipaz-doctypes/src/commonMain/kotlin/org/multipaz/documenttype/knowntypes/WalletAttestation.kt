package org.multipaz.documenttype.knowntypes

import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.cbor.toDataItem

object WalletAttestation {
    const val DOC_TYPE = "org.iso.18013.5.1.IT.WalletAttestation"
    const val NAMESPACE = "org.iso.18013.5.1.IT"

    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Wallet Attestation")
            .addMdocDocumentType(DOC_TYPE)
            .addMdocAttribute(
                DocumentAttributeType.String,
                "sub",
                "Wallet Instance Identifier",
                "Identifier of the Wallet Instance (COSE Key thumbprint)",
                true,
                NAMESPACE,
                Icon.PERSON,
                "vbeXJksM45xphtANnCiG6mCyuU4jfGNzopGuKvogg9c".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "aal",
                "Authentication Assurance Level",
                "Authentication level for the Wallet Instance COSE Key",
                true,
                NAMESPACE,
                Icon.FINGERPRINT,
                "https://trust-list.eu/aal/high".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "wallet_link",
                "Wallet Link",
                "URL for additional Wallet and Provider info",
                true,
                NAMESPACE,
                Icon.PERSON,
                "https://example.com/wallet/detail_info.html".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "wallet_name",
                "Wallet Provider Name",
                "Identifier of the Wallet Provider",
                true,
                NAMESPACE,
                Icon.PERSON,
                "Wallet_v1".toDataItem()
            )
            .addSampleRequest(
                id = "full_attestation",
                displayName = "Wallet Attestation (All Fields)",
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf(
                        "sub" to false,
                        "aal" to false,
                        "wallet_link" to false,
                        "wallet_name" to false
                    )
                )
            )
            .build()
    }
}