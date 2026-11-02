package com.example.pms.reservation

import com.example.pms.inventory.InventoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Component
class HoldExpirer(
	private val inventories: InventoryRepository,
	private val reservations: ReservationRepository,
	private val clock: Clock,
) {

	@Transactional
	fun expire(reservationId: Long) {
		val reservation = reservations.findByIdForUpdate(reservationId) ?: return
		// 조회와 잠금 사이에 결제가 먼저 처리됐을 수 있어 잠근 뒤 다시 판단
		if (!reservation.isHoldDue(clock.instant())) return

		val inventory = inventories.findAndLock(reservation.roomTypeId, reservation.period)
		inventories.save(inventory.releaseHold())
		reservations.updateStatus(reservation.expire())
	}
}
