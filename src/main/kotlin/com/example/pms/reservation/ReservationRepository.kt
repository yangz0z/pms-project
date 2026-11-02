package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import java.time.Instant

interface ReservationRepository {

	/**
	 * @return 같은 요청 키가 이미 있으면 null
	 */
	fun insertIfAbsent(reservation: NewReservation): Reservation?

	fun findByRequestKey(requestKey: String): Reservation?

	fun findByIdForUpdate(id: Long): Reservation?

	fun updateStatus(reservation: Reservation)

	fun updatePeriod(reservation: Reservation)

	fun findDueHoldIds(now: Instant, limit: Int): List<Long>

	fun findEarliestHoldExpiry(roomTypeId: Long, period: StayPeriod, excludingReservationId: Long? = null): Instant?
}
