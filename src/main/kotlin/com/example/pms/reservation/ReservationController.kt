package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import com.example.pms.web.ErrorResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

data class HoldRequest(
	// PostgreSQL 문자열은 NUL 문자를 저장할 수 없어 제어 문자를 입력 단계에서 거부
	@field:NotBlank @field:Size(max = 100) @field:Pattern(regexp = "\\P{Cntrl}*") val requestKey: String,
	@field:NotNull @field:Positive val roomTypeId: Long,
	@field:NotNull val checkIn: LocalDate,
	@field:NotNull val checkOut: LocalDate,
)

data class ReservationResponse(val id: Long, val status: ReservationStatus, val holdExpiresAt: Instant)

@RestController
@RequestMapping("/reservations")
class ReservationController(private val reservationService: ReservationService) {

	@PostMapping
	fun hold(@Valid @RequestBody request: HoldRequest): ResponseEntity<Any> {
		val command = HoldCommand(request.requestKey, request.roomTypeId, StayPeriod(request.checkIn, request.checkOut))
		return when (val result = reservationService.hold(command)) {
			is HoldResult.Held -> {
				val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
				val reservation = result.reservation
				ResponseEntity.status(status)
					.body(ReservationResponse(reservation.id, reservation.status, reservation.holdExpiresAt))
			}

			is HoldResult.SoldOut ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse("SOLD_OUT", "예약이 모두 찼습니다."))

			is HoldResult.TemporarilyHeld ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.header(HttpHeaders.RETRY_AFTER, result.retryAfterSeconds.toString())
					.body(ErrorResponse("TEMPORARILY_HELD", "다른 고객이 결제 중입니다. 잠시 후 다시 시도해 주세요."))
		}
	}
}
