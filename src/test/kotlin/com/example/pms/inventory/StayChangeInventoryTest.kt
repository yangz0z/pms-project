package com.example.pms.inventory

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StayChangeInventoryTest {

	private fun nov(day: Int) = LocalDate.of(2030, 11, day)

	private fun period(from: Int, until: Int) = StayPeriod(nov(from), nov(until))

	private fun day(date: Int, confirmed: Int = 0, held: Int = 0, total: Int = 1) =
		DailyInventory(1L, nov(date), total, confirmed, held)

	@Test
	fun `겹치는 날짜를 빼고 추가와 해제 날짜를 계산`() {
		val change = StayChange(period(10, 12), period(9, 11))

		assertEquals(listOf(nov(9)), change.addedNights)
		assertEquals(listOf(nov(11)), change.releasedNights)
		assertEquals(listOf(nov(9), nov(11)), change.affectedNights())
	}

	@Test
	fun `연장은 추가만, 단축은 해제만 발생`() {
		val extend = StayChange(period(10, 12), period(10, 13))
		val shorten = StayChange(period(10, 13), period(10, 12))

		assertEquals(listOf(nov(12)) to emptyList(), extend.addedNights to extend.releasedNights)
		assertEquals(emptyList<LocalDate>() to listOf(nov(12)), shorten.addedNights to shorten.releasedNights)
	}

	@Test
	fun `겹치지 않는 이동은 모든 날짜가 바뀌고 잠금 대상은 날짜순`() {
		val change = StayChange(period(20, 22), period(10, 12))

		assertEquals(listOf(nov(10), nov(11), nov(20), nov(21)), change.affectedNights())
	}

	@Test
	fun `점유 이동은 추가 날짜에 점유하고 해제 날짜의 점유를 해제`() {
		val inventory = StayChangeInventory(StayChange(period(10, 12), period(9, 11)), listOf(day(9), day(11, held = 1)))

		val moved = assertIs<ChangeAttempt.Success>(inventory.tryMoveHold())

		assertEquals(listOf(day(9, held = 1), day(11)), moved.days)
	}

	@Test
	fun `확정 이동은 확정 수량을 이동`() {
		val inventory = StayChangeInventory(StayChange(period(10, 12), period(9, 11)), listOf(day(9), day(11, confirmed = 1)))

		val moved = assertIs<ChangeAttempt.Success>(inventory.tryMoveConfirmed())

		assertEquals(listOf(day(9, confirmed = 1), day(11)), moved.days)
	}

	@Test
	fun `추가 날짜가 점유 중이면 일시 점유, 확정 만실이면 매진`() {
		val change = StayChange(period(10, 12), period(9, 11))

		assertIs<ChangeAttempt.TemporarilyHeld>(
			StayChangeInventory(change, listOf(day(9, held = 1), day(11, held = 1))).tryMoveHold(),
		)
		assertIs<ChangeAttempt.SoldOut>(
			StayChangeInventory(change, listOf(day(9, confirmed = 1), day(11, held = 1))).tryMoveHold(),
		)
	}

	@Test
	fun `추가 날짜의 재고 행이 없으면 매진`() {
		val change = StayChange(period(10, 12), period(9, 11))

		assertIs<ChangeAttempt.SoldOut>(StayChangeInventory(change, listOf(day(11, held = 1))).tryMoveHold())
	}

	@Test
	fun `해제 날짜의 재고 행이 없으면 데이터 오류로 중단`() {
		val change = StayChange(period(10, 12), period(9, 11))

		assertFailsWith<IllegalStateException> { StayChangeInventory(change, listOf(day(9))).tryMoveHold() }
	}
}
