package com.example.pms.inventory

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class StayInventoryTest {

	private val day1 = LocalDate.of(2030, 11, 10)
	private val day2 = LocalDate.of(2030, 11, 11)
	private val period = StayPeriod(day1, LocalDate.of(2030, 11, 12))

	private fun day(date: LocalDate, confirmed: Int = 0, held: Int = 0, total: Int = 1) =
		DailyInventory(1L, date, total, confirmed, held)

	@Test
	fun `체크아웃 날짜는 숙박일에 미포함`() {
		assertEquals(2L, period.nightCount())
	}

	@Test
	fun `30박까지 허용하고 초과하면 거부`() {
		assertEquals(30L, StayPeriod(day1, day1.plusDays(30)).nightCount())
		assertFailsWith<InvalidStayPeriodException> { StayPeriod(day1, day1.plusDays(31)) }
		assertFailsWith<InvalidStayPeriodException> { StayPeriod(day1, LocalDate.of(100000, 1, 1)) }
	}

	@Test
	fun `데이터베이스 범위를 넘는 날짜는 짧은 숙박이어도 거부`() {
		val farFuture = LocalDate.of(1000000, 1, 1)

		assertFailsWith<InvalidStayPeriodException> { StayPeriod(farFuture, farFuture.plusDays(1)) }
	}

	@Test
	fun `체크인이 체크아웃보다 늦거나 같으면 거부`() {
		assertFailsWith<InvalidStayPeriodException> { StayPeriod(day1, day1) }
	}

	@Test
	fun `가용 재고가 있으면 모든 숙박일을 점유`() {
		val attempt = StayInventory(period, listOf(day(day1), day(day2))).tryHold()

		val success = assertIs<HoldAttempt.Success>(attempt)
		assertEquals(listOf(1, 1), success.inventory.days.map { it.held })
	}

	@Test
	fun `점유 때문에 가용 재고가 없으면 일시 점유 상태`() {
		val attempt = StayInventory(period, listOf(day(day1, held = 1), day(day2))).tryHold()

		assertIs<HoldAttempt.TemporarilyHeld>(attempt)
	}

	@Test
	fun `하루라도 확정으로 만실이면 다른 날의 점유와 무관하게 매진`() {
		val attempt = StayInventory(period, listOf(day(day1, held = 1), day(day2, confirmed = 1))).tryHold()

		assertIs<HoldAttempt.SoldOut>(attempt)
	}

	@Test
	fun `판매하지 않는 날짜가 포함되면 매진`() {
		val attempt = StayInventory(period, listOf(day(day1))).tryHold()

		assertIs<HoldAttempt.SoldOut>(attempt)
	}

	@Test
	fun `점유를 확정하면 점유가 줄고 확정이 증가`() {
		val confirmed = StayInventory(period, listOf(day(day1, held = 1), day(day2, held = 1))).confirmHold()

		assertEquals(listOf(0, 0), confirmed.days.map { it.held })
		assertEquals(listOf(1, 1), confirmed.days.map { it.confirmed })
	}

	@Test
	fun `점유를 해제하면 가용 재고 복구`() {
		val released = StayInventory(period, listOf(day(day1, held = 1), day(day2, held = 1))).releaseHold()

		assertEquals(listOf(0, 0), released.days.map { it.held })
	}

	@Test
	fun `가용 재고가 없으면 직접 확정 불가`() {
		assertNull(StayInventory(period, listOf(day(day1, held = 1), day(day2))).tryConfirmDirectly())
	}

	@Test
	fun `전체 재고를 넘는 수량은 생성 불가`() {
		assertFailsWith<IllegalArgumentException> { day(day1, confirmed = 1, held = 1) }
	}
}
