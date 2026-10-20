package com.geovannycode.corteya.shared.internal

import com.geovannycode.corteya.shared.ApiFailure
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiFailure::class)
    fun known(failure: ApiFailure): ProblemDetail = problem(failure.status, failure.message)

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun contention(): ProblemDetail = problem(409, "Ese horario acaba de ser tomado, elige otro")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(failure: DataIntegrityViolationException): ProblemDetail {
        val sqlState = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        return if (sqlState == "23P01") problem(409, "Ese horario acaba de ser tomado, elige otro")
            else problem(409, "No pudimos completar la reserva con esos datos, intenta de nuevo")
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class, MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class)
    fun invalid(): ProblemDetail = problem(400,
        "Revisa los datos enviados: nombre, celular, código y fechas deben tener el formato solicitado")

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ProblemDetail {
        // Preserve HTTP semantics (404, 405, 415, etc.) without exposing framework messages.
        if (failure is ErrorResponse) {
            val status = failure.statusCode.value()
            return problem(status, when (status) {
                404 -> "No encontramos el recurso solicitado"
                405 -> "Ese método no está disponible para este recurso"
                415 -> "Envía los datos en formato JSON"
                else -> "No pudimos procesar la solicitud"
            })
        }
        logger.error("Error inesperado procesando la solicitud", failure)
        return problem(500, "No pudimos completar la solicitud, intenta de nuevo más tarde")
    }

    private fun problem(status: Int, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail).apply {
            title = when (status) {
                400 -> "Datos inválidos"
                401 -> "Autenticación requerida"
                403 -> "Acceso no permitido"
                422 -> "Perfil incompleto"
                429 -> "Demasiadas solicitudes"
                503 -> "Servicio temporalmente no disponible"
                404 -> "Recurso no encontrado"
                409 -> "No se pudo reservar"
                500 -> "Error inesperado"
                else -> "Solicitud no completada"
            }
        }
}
