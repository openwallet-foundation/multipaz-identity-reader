package org.multipaz.identityreader

/**
 * Thrown when using [ReaderBackendClient.getKey] with a reader identity that the caller
 * does not have access to.
 */
class ReaderIdentityNotAvailableException(message: String, cause: Throwable? = null): Exception(message, cause)