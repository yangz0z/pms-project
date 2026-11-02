package com.example.pms.web

import com.example.pms.inventory.InvalidStayPeriodException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(InvalidStayPeriodException::class)
	fun handleInvalidPeriod(e: InvalidStayPeriodException): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse(INVALID_REQUEST, e.message ?: "잘못된 숙박 기간입니다."))

	@ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
	fun handleInvalidBody(): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse(INVALID_REQUEST, "요청 형식이 올바르지 않습니다."))

	private companion object {
		const val INVALID_REQUEST = "INVALID_REQUEST"
	}
}
