package com.example.pms.inventory

sealed interface HoldAttempt {
	data class Success(val inventory: StayInventory) : HoldAttempt
	data object SoldOut : HoldAttempt
	data object TemporarilyHeld : HoldAttempt
}

/**
 * 숙박 기간 전체의 재고. 연박은 모든 숙박일이 함께 성공하거나 함께 실패
 */
class StayInventory(val period: StayPeriod, val days: List<DailyInventory>) {

	// 재고 행이 없는 날짜는 판매하지 않는 날짜
	private val coversAllNights = days.size.toLong() == period.nightCount()

	fun tryHold(): HoldAttempt = when {
		!coversAllNights || days.any { it.isSoldOut() } -> HoldAttempt.SoldOut
		days.any { !it.isAvailable() } -> HoldAttempt.TemporarilyHeld
		else -> HoldAttempt.Success(withDays { it.hold() })
	}

	fun releaseHold(): StayInventory = withDays { it.releaseHold() }

	fun confirmHold(): StayInventory = withDays { it.confirmHold() }

	/**
	 * 점유가 만료된 뒤 도착한 결제를 위해 점유 단계 없이 확정
	 * @return 가용 재고가 없으면 null
	 */
	fun tryConfirmDirectly(): StayInventory? =
		if (coversAllNights && days.all { it.isAvailable() }) withDays { it.confirmDirectly() } else null

	private fun withDays(change: (DailyInventory) -> DailyInventory) = StayInventory(period, days.map(change))
}
