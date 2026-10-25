package com.geovannycode.corteya.shared



/** Safe status/message contract for known application failures. */
open class ApiFailure(val status: Int, override val message: String, val properties: Map<String, Any> = emptyMap()) : RuntimeException(message)
