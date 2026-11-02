package com.example.pms.payment

import com.example.pms.reservation.ReservationStatus
import com.example.pms.web.ErrorResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PaymentCallbackRequest(
	// PostgreSQL 문자열은 NUL 문자를 저장할 수 없어 제어 문자를 입력 단계에서 거부
	@field:NotBlank @field:Size(max = 100) @field:Pattern(regexp = "\\P{Cntrl}*") val paymentId: String,
	@field:Positive val reservationId: Long,
)

data class PaymentCallbackResponse(val reservationId: Long, val status: ReservationStatus, val duplicate: Boolean)

@RestController
@RequestMapping("/payments")
class PaymentController(private val paymentService: PaymentService) {

	@PostMapping("/callback")
	fun callback(@Valid @RequestBody request: PaymentCallbackRequest): ResponseEntity<Any> =
		when (val result = paymentService.handleSuccess(request.paymentId, request.reservationId)) {
			is PaymentResult.Processed ->
				ResponseEntity.ok(PaymentCallbackResponse(request.reservationId, result.status, result.duplicate))

			is PaymentResult.PaymentConflict ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse("PAYMENT_CONFLICT", "다른 예약에 이미 처리된 결제입니다."))

			is PaymentResult.ReservationNotFound ->
				ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(ErrorResponse("RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."))
		}
}
