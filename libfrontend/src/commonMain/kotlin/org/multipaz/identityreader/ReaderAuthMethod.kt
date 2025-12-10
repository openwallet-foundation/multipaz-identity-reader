package org.multipaz.identityreader

enum class ReaderAuthMethod {
    NO_READER_AUTH,
    CUSTOM_KEY,
    STANDARD_READER_AUTH,
    STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS,
    IDENTITY_FROM_GOOGLE_ACCOUNT,
}
