package com.example.pms.payment

import java.time.Duration
import java.time.Instant

enum class RefundStatus { PENDING, COMPLETED, NEEDS_REVIEW }

/**
 * 생성자와 copy를 숨겨 상태는 전이 메서드로만 변경
 */
@ConsistentCopyVisibility
data class RefundRequest private constructor(
	val id: Long,
	val paymentId: String,
	val reservationId: Long,
	val status: RefundStatus,
	val attempts: Int,
	val nextAttemptAt: Instant,
	val lastError: String?,
) {

	fun complete(): RefundRequest {
		checkPending()
		return copy(status = RefundStatus.COMPLETED, lastError = null)
	}

	fun retryLater(now: Instant, reason: String): RefundRequest {
		checkPending()
		val delay = RETRY_DELAYS.getOrNull(attempts - 1) ?: return escalate(reason)
		return copy(nextAttemptAt = now.plus(delay), lastError = reason.take(MAX_ERROR_LENGTH))
	}

	fun escalate(reason: String): RefundRequest {
		checkPending()
		return copy(status = RefundStatus.NEEDS_REVIEW, lastError = reason.take(MAX_ERROR_LENGTH))
	}

	private fun checkPending() = check(status == RefundStatus.PENDING) { "대기 상태만 처리 가능: $status" }

	companion object {
		private const val MAX_ERROR_LENGTH = 500

		// 고객이 기다리는 환불이라 일시 장애는 빠르게 재시도하고, 장애가 길어지면 간격을 늘림
		private val RETRY_DELAYS = listOf(
			Duration.ofSeconds(10),
			Duration.ofMinutes(1),
			Duration.ofMinutes(5),
			Duration.ofMinutes(15),
		)

		fun restore(
			id: Long,
			paymentId: String,
			reservationId: Long,
			status: RefundStatus,
			attempts: Int,
			nextAttemptAt: Instant,
			lastError: String?,
		) = RefundRequest(id, paymentId, reservationId, status, attempts, nextAttemptAt, lastError)
	}
}
