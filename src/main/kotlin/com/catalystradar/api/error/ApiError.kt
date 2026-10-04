package com.catalystradar.api.error

import jakarta.servlet.http.HttpServletRequest
import com.catalystradar.application.catalyst.CatalystNotFoundException
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.operations.OperationsResource
import com.catalystradar.application.operations.OperationsResourceNotFoundException
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

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(OperationsResourceNotFoundException::class)
    fun operationsResourceNotFound(e: OperationsResourceNotFoundException, request: HttpServletRequest): ResponseEntity<ProblemResponse> {
        val (code, title, detail) = when (e.resource) {
            OperationsResource.DOCUMENT -> Triple("DOCUMENT_NOT_FOUND", "Document not found", "Source document was not found")
            OperationsResource.OPERATION_RUN -> Triple("OPERATION_RUN_NOT_FOUND", "Operation run not found", "Operation run was not found")
            OperationsResource.VALUATION -> Triple("VALUATION_NOT_FOUND", "Valuation not found", "Company valuation was not found")
            OperationsResource.MODEL_RUN -> Triple("MODEL_RUN_NOT_FOUND", "Model run not found", "Model run was not found")
        }
        return problem(HttpStatus.NOT_FOUND, code, title, detail, request)
    }

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
