package org.multipaz.documenttype


/**
 * Base class for a well-known document request.
 *
 * @param id an identifier for the well-known document request (unique only for the document type).
 * @param displayName a short string with the name of the request, short enough to be used
 *   for a button. For example "Age Over 21 and Portrait" or "Full mDL".
 * @param mdocRequest the requests for a ISO mdoc credential, if defined.
 * @param jsonRequest the requests for a JSON-based credential, if defined.
 */
sealed class DocumentCannedRequest(
    open val id: String,
    open val displayName: String
)
