package com.geovannycode.corteya.identity.dto

import com.geovannycode.corteya.identity.PhoneNumbers
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RequestOtpBody(@field:NotBlank @field:Size(max = 16) val phone: String) {
    override fun toString(): String = "RequestOtpBody(phone=${PhoneNumbers.mask(phone)})"
}
data class VerifyOtpBody(@field:NotBlank @field:Size(max = 16) val phone: String, @field:NotBlank @field:Size(max = 64) val code: String) {
    override fun toString(): String = "VerifyOtpBody(phone=${PhoneNumbers.mask(phone)}, code=[redacted])"
}
data class RefreshBody(@field:NotBlank @field:Size(max = 128) val refreshToken: String) {
    override fun toString(): String = "RefreshBody([redacted])"
}
data class OtpRequestResponse(val message: String = "Si el número es válido, recibirás un código de verificación")
