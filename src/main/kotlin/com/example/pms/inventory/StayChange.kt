package com.example.pms.inventory

import java.time.LocalDate

/**
 * 겹치는 숙박일은 이미 확보한 재고이므로 달라지는 날짜만 계산
 */
class StayChange(current: StayPeriod, requested: StayPeriod) {

	val addedNights: List<LocalDate> = requested.nights() - current.nights().toSet()

	val releasedNights: List<LocalDate> = current.nights() - requested.nights().toSet()

	/**
	 * 변경 간 교착 상태 방지를 위해 추가와 해제 날짜를 합쳐 날짜순으로 정렬
	 */
	fun affectedNights(): List<LocalDate> = (addedNights + releasedNights).sorted()
}

sealed interface ChangeAttempt {
	data class Success(val days: List<DailyInventory>) : ChangeAttempt
	data object SoldOut : ChangeAttempt
	data object TemporarilyHeld : ChangeAttempt
}

class StayChangeInventory(change: StayChange, days: List<DailyInventory>) {

	private val added = days.filter { it.stayDate in change.addedNights }
	private val released = days.filter { it.stayDate in change.releasedNights }

	// 재고 행이 없는 날짜는 판매하지 않는 날짜
	private val coversAddedNights = added.size == change.addedNights.size

	init {
		check(released.size == change.releasedNights.size) { "예약 숙박일의 재고 행이 없음" }
	}

	fun tryMoveHold(): ChangeAttempt = tryMove(DailyInventory::hold, DailyInventory::releaseHold)

	fun tryMoveConfirmed(): ChangeAttempt = tryMove(DailyInventory::confirmDirectly, DailyInventory::releaseConfirmed)

	private fun tryMove(
		acquire: (DailyInventory) -> DailyInventory,
		release: (DailyInventory) -> DailyInventory,
	): ChangeAttempt = when {
		!coversAddedNights || added.any { it.isSoldOut() } -> ChangeAttempt.SoldOut
		added.any { !it.isAvailable() } -> ChangeAttempt.TemporarilyHeld
		else -> ChangeAttempt.Success((added.map(acquire) + released.map(release)).sortedBy { it.stayDate })
	}
}
