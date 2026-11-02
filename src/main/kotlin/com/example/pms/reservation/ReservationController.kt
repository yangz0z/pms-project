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
import org.springframework.web.bind.annotation.PathVariable
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

data class DateChangeRequest(
	// PostgreSQL 문자열은 NUL 문자를 저장할 수 없어 제어 문자를 입력 단계에서 거부
	@field:NotBlank @field:Size(max = 100) @field:Pattern(regexp = "\\P{Cntrl}*") val requestKey: String,
	@field:Positive val version: Int,
	@field:NotNull val checkIn: LocalDate,
	@field:NotNull val checkOut: LocalDate,
)

data class ReservationResponse(
	val id: Long,
	val status: ReservationStatus,
	val version: Int,
	val checkIn: LocalDate,
	val checkOut: LocalDate,
	val holdExpiresAt: Instant,
) {
	constructor(reservation: Reservation) : this(
		reservation.id,
		reservation.status,
		reservation.version,
		reservation.period.checkIn,
		reservation.period.checkOut,
		reservation.holdExpiresAt,
	)
}

@RestController
@RequestMapping("/reservations")
class ReservationController(
	private val reservationService: ReservationService,
	private val dateChangeService: ReservationDateChangeService,
) {

	@PostMapping
	fun hold(@Valid @RequestBody request: HoldRequest): ResponseEntity<Any> {
		val command = HoldCommand(request.requestKey, request.roomTypeId, StayPeriod(request.checkIn, request.checkOut))
		return when (val result = reservationService.hold(command)) {
			is HoldResult.Held -> {
				val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
				ResponseEntity.status(status).body(ReservationResponse(result.reservation))
			}

			is HoldResult.SoldOut -> soldOut()
			is HoldResult.TemporarilyHeld -> temporarilyHeld(result.retryAfterSeconds)
			is HoldResult.RequestKeyConflict ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse("REQUEST_KEY_CONFLICT", "같은 요청 키로 다른 내용의 예약이 이미 있습니다."))
		}
	}

	@PostMapping("/{id}/date-change")
	fun changeDates(@PathVariable id: Long, @Valid @RequestBody request: DateChangeRequest): ResponseEntity<Any> {
		val command = DateChangeCommand(
			id,
			request.requestKey,
			request.version,
			StayPeriod(request.checkIn, request.checkOut),
		)
		return when (val result = dateChangeService.change(command)) {
			is DateChangeResult.Changed -> ResponseEntity.ok(ReservationResponse(result.reservation))
			is DateChangeResult.ReservationNotFound ->
				ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(ErrorResponse("RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."))

			is DateChangeResult.NotChangeable ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse("NOT_CHANGEABLE", "날짜를 변경할 수 없는 예약입니다."))

			is DateChangeResult.ReservationChanged ->
				ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse("RESERVATION_CHANGED", "예약 날짜가 이미 변경되었습니다. 최신 예약을 확인해 주세요."))

			is DateChangeResult.SoldOut -> soldOut()
			is DateChangeResult.TemporarilyHeld -> temporarilyHeld(result.retryAfterSeconds)
		}
	}

	private fun soldOut(): ResponseEntity<Any> =
		ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ErrorResponse("SOLD_OUT", "예약이 모두 찼습니다."))

	private fun temporarilyHeld(retryAfterSeconds: Long): ResponseEntity<Any> =
		ResponseEntity.status(HttpStatus.CONFLICT)
			.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
			.body(ErrorResponse("TEMPORARILY_HELD", "다른 고객이 결제 중입니다. 잠시 후 다시 시도해 주세요."))
}
