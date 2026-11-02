package com.example.pms.payment

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class RefundRequestResponse(
	val id: Long,
	val paymentId: String,
	val reservationId: Long,
	val status: RefundStatus,
	val attempts: Int,
	val nextAttemptAt: Instant,
	val lastError: String?,
)

@RestController
@RequestMapping("/ops/refund-requests")
class RefundRequestController(private val requests: RefundRequestRepository) {

	@GetMapping
	fun findByStatus(@RequestParam status: RefundStatus): List<RefundRequestResponse> =
		requests.findByStatus(status, MAX_RESULTS).map {
			RefundRequestResponse(it.id, it.paymentId, it.reservationId, it.status, it.attempts, it.nextAttemptAt, it.lastError)
		}

	private companion object {
		const val MAX_RESULTS = 100
	}
}
