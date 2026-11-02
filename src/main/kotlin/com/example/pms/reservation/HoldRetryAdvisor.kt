package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class HoldRetryAdvisor(
	private val reservations: ReservationRepository,
	private val clock: Clock,
) {

	fun retryAfterSeconds(roomTypeId: Long, period: StayPeriod, excludingReservationId: Long? = null): Long {
		val expiry = reservations.findEarliestHoldExpiry(roomTypeId, period, excludingReservationId)
			?: return MIN_RETRY_AFTER_SECONDS
		return Duration.between(clock.instant(), expiry).seconds.coerceAtLeast(MIN_RETRY_AFTER_SECONDS)
	}

	private companion object {
		const val MIN_RETRY_AFTER_SECONDS = 1L
	}
}
