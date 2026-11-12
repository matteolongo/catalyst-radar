package com.catalystradar.api.error

import com.catalystradar.api.RequestIdFilter
import com.catalystradar.application.catalyst.CatalystNotFoundException
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.operations.OperationsResource
import com.catalystradar.application.operations.OperationsResourceNotFoundException
import com.catalystradar.common.InvalidInputException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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

    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

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

    /**
     * Only [InvalidInputException] is a caller error. A plain
     * IllegalArgumentException is an internal invariant failure and falls
     * through to [unexpected] so it is logged and its message stays private.
     */
    @ExceptionHandler(InvalidInputException::class, MethodArgumentTypeMismatchException::class)
    fun badRequest(e: Exception, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "Invalid request",
            if (e is MethodArgumentTypeMismatchException) {
                "Invalid value for parameter '${e.name}'. Choose a supported value."
            } else {
                e.message ?: "Invalid request"
            },
            request,
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ProblemResponse> =
        problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "Invalid request",
            "Request body is malformed or missing required fields.",
            request,
        )

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ProblemResponse> {
        // Spring MVC's own failures (unknown route, unsupported method or
        // media type, missing parameter) carry their intended status.
        if (e is ErrorResponse && !e.statusCode.is5xxServerError) {
            val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.BAD_REQUEST
            val code = when (status) {
                HttpStatus.NOT_FOUND -> "NOT_FOUND"
                HttpStatus.METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED"
                HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE"
                HttpStatus.NOT_ACCEPTABLE -> "NOT_ACCEPTABLE"
                else -> "INVALID_REQUEST"
            }
            return problem(status, code, status.reasonPhrase, "${status.reasonPhrase}: ${request.method} ${request.requestURI}", request)
        }
        log.error("unhandled exception handling {} {}", request.method, request.requestURI, e)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal error",
            "Unexpected failure handling ${request.method} ${request.requestURI}",
            request,
        )
    }

    private fun problem(
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemResponse> {
        val requestId = RequestIdFilter.of(request)
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
}
