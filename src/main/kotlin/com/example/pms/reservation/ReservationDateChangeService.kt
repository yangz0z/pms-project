package com.example.pms.reservation

import com.example.pms.inventory.ChangeAttempt
import com.example.pms.inventory.InvalidStayPeriodException
import com.example.pms.inventory.InventoryRepository
import com.example.pms.inventory.StayChange
import com.example.pms.inventory.StayChangeInventory
import com.example.pms.inventory.StayPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

data class DateChangeCommand(
	val reservationId: Long,
	val requestKey: String,
	val expectedVersion: Int,
	val period: StayPeriod,
)

sealed interface DateChangeResult {
	data class Changed(val reservation: Reservation) : DateChangeResult
	data object ReservationNotFound : DateChangeResult
	data object NotChangeable : DateChangeResult
	data object ReservationChanged : DateChangeResult
	data object SoldOut : DateChangeResult
	data class TemporarilyHeld(val retryAfterSeconds: Long) : DateChangeResult
}

@Service
class ReservationDateChangeService(
	private val reservations: ReservationRepository,
	private val inventories: InventoryRepository,
	private val retryAdvisor: HoldRetryAdvisor,
	private val clock: Clock,
) {

	@Transactional
	fun change(command: DateChangeCommand): DateChangeResult {
		// 결제, 만료와 같은 잠금 순서(예약 행 → 재고 행)를 유지
		val reservation = reservations.findByIdForUpdate(command.reservationId)
			?: return DateChangeResult.ReservationNotFound
		if (reservation.requestKey != command.requestKey) return DateChangeResult.ReservationNotFound
		if (!reservation.isChangeable(clock.instant())) return DateChangeResult.NotChangeable
		if (reservation.period == command.period) return DateChangeResult.Changed(reservation)
		// 지연된 이전 요청이 최신 변경을 되돌리지 않도록 요청 시점의 버전과 비교
		if (reservation.version != command.expectedVersion) return DateChangeResult.ReservationChanged

		val today = LocalDate.now(clock)
		// 입력을 고쳐도 해결되지 않는 예약 상태 문제이므로 입력 오류와 구분
		if (reservation.period.startsOnOrBefore(today)) return DateChangeResult.NotChangeable
		if (command.period.startsOnOrBefore(today)) {
			throw InvalidStayPeriodException("당일 및 지난 날짜로 변경 불가")
		}

		val change = StayChange(reservation.period, command.period)
		val inventory = StayChangeInventory(
			change,
			inventories.findAndLockNights(reservation.roomTypeId, change.affectedNights()),
		)
		val attempt = if (reservation.status == ReservationStatus.HOLD) {
			inventory.tryMoveHold()
		} else {
			inventory.tryMoveConfirmed()
		}

		return when (attempt) {
			is ChangeAttempt.SoldOut -> DateChangeResult.SoldOut
			is ChangeAttempt.TemporarilyHeld -> DateChangeResult.TemporarilyHeld(
				retryAdvisor.retryAfterSeconds(reservation.roomTypeId, command.period, reservation.id),
			)
			is ChangeAttempt.Success -> {
				inventories.save(attempt.days)
				val changed = reservation.changePeriod(command.period)
				reservations.updatePeriod(changed)
				DateChangeResult.Changed(changed)
			}
		}
	}
}
