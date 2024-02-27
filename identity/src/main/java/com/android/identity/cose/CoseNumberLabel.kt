package com.android.identity.cose

import com.android.identity.cbor.DataItem
import com.android.identity.cbor.toDataItem

/**
 * A COSE Label for a number.
 *
 * @param number the number.
 */
data class CoseNumberLabel(val number: Long) : CoseLabel() {
    override val toDataItem: DataItem
        get() = number.toDataItem
}

/**
 * Gets a [CoseLabel] from a number.
 */
val Long.toCoseLabel
    get() = CoseNumberLabel(this)
