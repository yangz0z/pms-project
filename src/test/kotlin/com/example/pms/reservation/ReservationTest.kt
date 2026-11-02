package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReservationTest {

	private fun reservation(status: ReservationStatus) = Reservation.restore(
		id = 1L,
		requestKey = "req-1",
		roomTypeId = 1L,
		period = StayPeriod(LocalDate.of(2030, 11, 10), LocalDate.of(2030, 11, 12)),
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
}
