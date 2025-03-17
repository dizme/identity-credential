package org.multipaz.crypto

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborArray

/**
 * A chain of certificates.
 *
 * @param certificates the certificates in the chain.
 */
@CborSerializationImplemented(schemaId = "62socrrUk8v-bXSe8dniBNlhAT06JKs8_DpQlH53sZQ")
data class X509CertChain(
    val certificates: List<X509Cert>
) {

    /**
     * Encodes the certificate chain as CBOR.
     *
     * If the chain has only one item a [Bstr] with the sole certificate is returned.
     * Otherwise an array of [Bstr] is returned.
     *
     * Use [fromDataItem] to decode the returned data item.
     */
    fun toDataItem(): DataItem {
        if (certificates.size == 1) {
            return certificates[0].toDataItem()
        } else {
            return buildCborArray {
                certificates.forEach { certificate -> add(certificate.toDataItem()) }
            }
        }
    }

    /**
     * Validates that every certificate in the chain is signed by the next one.
     *
     * @return true if every certificate in the chain is signed by the next one, false otherwise.
     */
    fun validate(): Boolean = Crypto.validateCertChain(this)

    companion object {
        /**
         * Decodes a certificate chain from CBOR.
         *
         * See [Certificate.toDataItem] for the expected encoding.
         *
         * @param dataItem the CBOR data item to decode.
         * @return the certificate chain.
         */
        fun fromDataItem(dataItem: DataItem): X509CertChain {
            val certificates: List<X509Cert> =
                if (dataItem is CborArray) {
                    dataItem.items.map { item -> item.asX509Cert }.toList()
                } else {
                    listOf(dataItem.asX509Cert)
                }
            return X509CertChain(certificates)
        }
    }
}
