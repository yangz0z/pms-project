package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservationTest {

	private fun reservation(status: ReservationStatus) = Reservation.restore(
		id = 1L,
		requestKey = "req-1",
		roomTypeId = 1L,
		period = PERIOD,
		requestedPeriod = PERIOD,
		version = 1,
		status = status,
		holdExpiresAt = Instant.parse("2030-01-01T00:10:00Z"),
	)

	@Test
	fun `점유 상태와 만료 상태만 확정 가능`() {
		assertEquals(ReservationStatus.CONFIRMED, reservation(ReservationStatus.HOLD).confirm().status)
		assertEquals(ReservationStatus.CONFIRMED, reservation(ReservationStatus.EXPIRED).confirm().status)
		assertFailsWith<IllegalStateException> { reservation(ReservationStatus.REFUNDED).confirm() }
	}

	@Test
	fun `점유 상태만 만료 가능`() {
		assertEquals(ReservationStatus.EXPIRED, reservation(ReservationStatus.HOLD).expire().status)
		assertFailsWith<IllegalStateException> { reservation(ReservationStatus.CONFIRMED).expire() }
	}

	@Test
	fun `만료 상태만 환불 가능`() {
		assertEquals(ReservationStatus.REFUNDED, reservation(ReservationStatus.EXPIRED).refund().status)
		assertFailsWith<IllegalStateException> { reservation(ReservationStatus.HOLD).refund() }
	}

	@Test
	fun `점유와 확정 상태만 날짜 변경 가능하고 처음 요청한 기간과 만료 시각은 유지`() {
		val moved = StayPeriod(LocalDate.of(2030, 11, 9), LocalDate.of(2030, 11, 11))

		val changed = reservation(ReservationStatus.HOLD).changePeriod(moved)

		assertEquals(moved, changed.period)
		assertEquals(PERIOD, changed.requestedPeriod)
		assertEquals(2, changed.version)
		assertEquals(reservation(ReservationStatus.HOLD).holdExpiresAt, changed.holdExpiresAt)
		assertEquals(moved, reservation(ReservationStatus.CONFIRMED).changePeriod(moved).period)
		assertFailsWith<IllegalStateException> { reservation(ReservationStatus.EXPIRED).changePeriod(moved) }
		assertFailsWith<IllegalStateException> { reservation(ReservationStatus.REFUNDED).changePeriod(moved) }
	}

	@Test
	fun `같은 객실 타입과 처음 요청한 기간이어야 같은 신청으로 판단`() {
		val changed = reservation(ReservationStatus.HOLD)
			.changePeriod(StayPeriod(LocalDate.of(2030, 11, 9), LocalDate.of(2030, 11, 11)))

		assertTrue(changed.matchesRequest(1L, PERIOD))
		assertFalse(changed.matchesRequest(2L, PERIOD))
		assertFalse(changed.matchesRequest(1L, changed.period))
	}

	private companion object {
		val PERIOD = StayPeriod(LocalDate.of(2030, 11, 10), LocalDate.of(2030, 11, 12))
	}
}
