package dev.zxilly.jenkins.cnb.api

import java.io.IOException

class CnbApiException(
    message: String,
    val statusCode: Int = 0,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)
