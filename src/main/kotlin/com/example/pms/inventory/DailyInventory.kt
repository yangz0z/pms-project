package com.example.pms.inventory

import java.time.LocalDate

data class DailyInventory(
	val roomTypeId: Long,
	val stayDate: LocalDate,
	val total: Int,
	val confirmed: Int,
	val held: Int,
) {

	init {
		require(confirmed >= 0 && held >= 0) { "재고 수량은 음수 불가" }
		require(confirmed + held <= total) { "확정과 점유의 합은 전체 재고 초과 불가" }
	}

	fun isSoldOut(): Boolean = confirmed >= total

	fun isAvailable(): Boolean = confirmed + held < total

	fun hold(): DailyInventory = copy(held = held + 1)

	fun releaseHold(): DailyInventory = copy(held = held - 1)

	fun confirmHold(): DailyInventory = copy(held = held - 1, confirmed = confirmed + 1)

	fun confirmDirectly(): DailyInventory = copy(confirmed = confirmed + 1)

	fun releaseConfirmed(): DailyInventory = copy(confirmed = confirmed - 1)
}
