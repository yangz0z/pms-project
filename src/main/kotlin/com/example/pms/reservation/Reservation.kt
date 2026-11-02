package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import java.time.Instant

enum class ReservationStatus { HOLD, CONFIRMED, EXPIRED, REFUNDED }

data class NewReservation(
	val requestKey: String,
	val roomTypeId: Long,
	val period: StayPeriod,
	val holdExpiresAt: Instant,
)

/**
 * 생성자와 copy를 숨겨 상태는 전이 메서드로만 변경
 */
@ConsistentCopyVisibility
data class Reservation private constructor(
	val id: Long,
	val requestKey: String,
	val roomTypeId: Long,
	val period: StayPeriod,
	val status: ReservationStatus,
	val holdExpiresAt: Instant,
) {

	fun awaitsPayment(): Boolean = status == ReservationStatus.HOLD || status == ReservationStatus.EXPIRED

	fun isHoldDue(now: Instant): Boolean = status == ReservationStatus.HOLD && !holdExpiresAt.isAfter(now)

	fun confirm(): Reservation {
		check(awaitsPayment()) { "결제 대기 상태만 확정 가능: $status" }
		return copy(status = ReservationStatus.CONFIRMED)
	}

	fun expire(): Reservation {
		check(status == ReservationStatus.HOLD) { "점유 상태만 만료 가능: $status" }
		return copy(status = ReservationStatus.EXPIRED)
	}

	fun refund(): Reservation {
		check(status == ReservationStatus.EXPIRED) { "만료 상태만 환불 가능: $status" }
		return copy(status = ReservationStatus.REFUNDED)
	}

	companion object {
		fun restore(
			id: Long,
			requestKey: String,
			roomTypeId: Long,
			period: StayPeriod,
			status: ReservationStatus,
			holdExpiresAt: Instant,
		) = Reservation(id, requestKey, roomTypeId, period, status, holdExpiresAt)
	}
}
