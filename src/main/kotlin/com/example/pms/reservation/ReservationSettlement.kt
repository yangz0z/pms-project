package com.example.pms.reservation

import com.example.pms.inventory.InventoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 결제 완료를 예약과 재고에 반영
 * 호출 전에 예약 행을 잠가 만료 작업과의 경합을 차단해야 함
 */
@Component
class ReservationSettlement(
	private val inventories: InventoryRepository,
	private val reservations: ReservationRepository,
) {

	@Transactional(propagation = Propagation.MANDATORY)
	fun settle(reservation: Reservation): Reservation {
		val settled = when (reservation.status) {
			ReservationStatus.HOLD -> {
				val inventory = inventories.findAndLock(reservation.roomTypeId, reservation.period)
				inventories.save(inventory.confirmHold())
				reservation.confirm()
			}

			ReservationStatus.EXPIRED -> {
				val inventory = inventories.findAndLock(reservation.roomTypeId, reservation.period)
				val confirmed = inventory.tryConfirmDirectly()
				if (confirmed != null) {
					inventories.save(confirmed)
					reservation.confirm()
				} else {
					reservation.refund()
				}
			}

			ReservationStatus.CONFIRMED, ReservationStatus.REFUNDED -> return reservation
		}
		reservations.updateStatus(settled)
		return settled
	}
}
