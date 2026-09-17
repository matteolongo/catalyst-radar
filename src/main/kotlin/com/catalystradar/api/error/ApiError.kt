package com.catalystradar.api.error

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

/**
 * Stable machine-readable errors in RFC 9457 shape. No stack traces or
 * provider payloads ever reach API clients.
 */
data class ProblemResponse(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val requestId: String,
)

class CompanyNotFoundException(ticker: String) :
    RuntimeException("No supported company exists for ticker $ticker") {
    val ticker: String = ticker
}

class CatalystNotFoundException(ticker: String) :
    RuntimeException("No catalyst state exists for ticker $ticker") {
    val ticker: String = ticker
}

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(CompanyNotFoundException::class)
    fun companyNotFound(e: CompanyNotFoundException, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.NOT_FOUND,
            "COMPANY_NOT_FOUND",
            "Company not found",
            e.message ?: "Company ${e.ticker} was not found",
            request,
        )

    @ExceptionHandler(CatalystNotFoundException::class)
    fun catalystNotFound(e: CatalystNotFoundException, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.NOT_FOUND,
            "CATALYST_NOT_FOUND",
            "Catalyst state not found",
            e.message ?: "Catalyst state for ${e.ticker} was not found",
            request,
        )

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun badRequest(e: Exception, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "Invalid request",
            rootMessage(e),
            request,
        )

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal error",
            "Unexpected failure handling ${request.method} ${request.requestURI}",
            request,
        )

    private fun problem(
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemResponse> {
        val requestId = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return ResponseEntity.status(status).body(
            ProblemResponse(
                type = "https://catalystradar.dev/problems/${code.lowercase().replace('_', '-')}",
                title = title,
                status = status.value(),
                code = code,
                detail = detail,
                requestId = requestId,
            ),
        )
    }

    private fun rootMessage(e: Exception): String {
        var current: Throwable = e
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: e.javaClass.simpleName
    }
}
