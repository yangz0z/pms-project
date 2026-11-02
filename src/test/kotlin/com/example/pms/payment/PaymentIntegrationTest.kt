package com.example.pms.payment

import com.example.pms.inventory.StayPeriod
import com.example.pms.reservation.HoldCommand
import com.example.pms.reservation.HoldResult
import com.example.pms.reservation.ReservationService
import com.example.pms.reservation.ReservationStatus
import com.example.pms.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PaymentIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var reservations: ReservationService

	@Autowired
	private lateinit var payments: PaymentService

	private fun hold(key: String): Long {
		val held = assertIs<HoldResult.Held>(reservations.hold(HoldCommand(key, 1L, StayPeriod(checkIn, checkOut))))
		return held.reservation.id
	}

	private fun expireHolds() {
		clock.advance(Duration.ofMinutes(10))
		reservations.expireDueHolds()
	}

	@Test
	fun `점유 중 결제 성공이면 확정`() {
		stock()
		val id = hold("req-1")

		val result = assertIs<PaymentResult.Processed>(payments.handleSuccess("pay-1", id))

		assertEquals(ReservationStatus.CONFIRMED, result.status)
		assertEquals(false, result.duplicate)
		assertEquals(listOf(0, 0), heldCounts())
		assertEquals(listOf(1, 1), confirmedCounts())
	}

	@Test
	fun `같은 결제 알림이 동시에 여러 번 와도 확정은 1번`() {
		stock(total = 3)
		val id = hold("req-1")
		val pool = Executors.newFixedThreadPool(10)
		val start = CountDownLatch(1)

		val results = try {
			val futures = (1..10).map {
				pool.submit(Callable {
					start.await()
					payments.handleSuccess("pay-1", id)
				})
			}
			start.countDown()
			futures.map { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		assertEquals(1, results.count { !(it as PaymentResult.Processed).duplicate })
		assertEquals(listOf(1, 1), confirmedCounts())
		assertEquals(listOf(0, 0), heldCounts())
	}

	@Test
	fun `결제 없이 만료되면 재고 복구`() {
		stock()
		val id = hold("req-1")

		expireHolds()

		assertEquals("EXPIRED", statusOf(id))
		assertEquals(listOf(0, 0), heldCounts())
	}

	@Test
	fun `만료 시각 전에는 점유 유지`() {
		stock()
		val id = hold("req-1")
		clock.advance(Duration.ofMinutes(10).minusSeconds(1))

		reservations.expireDueHolds()

		assertEquals("HOLD", statusOf(id))
	}

	@Test
	fun `만료 후 결제 성공이고 재고가 남으면 재고를 다시 잡아 확정`() {
		stock()
		val id = hold("req-1")
		expireHolds()

		val result = assertIs<PaymentResult.Processed>(payments.handleSuccess("pay-1", id))

		assertEquals(ReservationStatus.CONFIRMED, result.status)
		assertEquals(listOf(1, 1), confirmedCounts())
		assertEquals(emptyList(), refundRequestPaymentIds())
	}

	@Test
	fun `만료 후 결제 성공인데 다른 고객이 점유했으면 환불`() {
		stock()
		val late = hold("req-1")
		expireHolds()
		hold("req-2")

		val result = assertIs<PaymentResult.Processed>(payments.handleSuccess("pay-late", late))

		assertEquals(ReservationStatus.REFUNDED, result.status)
		assertEquals(listOf("pay-late"), refundRequestPaymentIds())
		assertEquals(listOf(1, 1), heldCounts())
		assertEquals(listOf(0, 0), confirmedCounts())
	}

	@Test
	fun `확정된 예약에 다른 결제가 도착하면 그 결제만 환불하고 예약은 유지`() {
		stock(total = 2)
		val id = hold("req-1")
		payments.handleSuccess("pay-1", id)

		val result = assertIs<PaymentResult.Processed>(payments.handleSuccess("pay-2", id))

		assertEquals(ReservationStatus.CONFIRMED, result.status)
		assertEquals(listOf("pay-2"), refundRequestPaymentIds())
		assertEquals(listOf(1, 1), confirmedCounts())
	}

	@Test
	fun `환불된 예약에 다른 결제가 도착해도 그 결제만 환불`() {
		stock()
		val late = hold("req-1")
		expireHolds()
		hold("req-2")
		payments.handleSuccess("pay-1", late)

		val result = assertIs<PaymentResult.Processed>(payments.handleSuccess("pay-2", late))

		assertEquals(ReservationStatus.REFUNDED, result.status)
		assertEquals(listOf("pay-1", "pay-2"), refundRequestPaymentIds())
	}

	@Test
	fun `만료 후 결제와 새 신청이 동시에 와도 재고를 초과하지 않음`() {
		stock()
		repeat(10) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val late = hold("req-late-$n")
			expireHolds()
			val pool = Executors.newFixedThreadPool(2)
			val start = CountDownLatch(1)
			val (payment, newHold) = try {
				val paymentFuture = pool.submit(Callable { start.await(); payments.handleSuccess("pay-$n", late) })
				val holdFuture = pool.submit(Callable {
					start.await()
					reservations.hold(HoldCommand("req-new-$n", 1L, StayPeriod(checkIn, checkOut)))
				})
				start.countDown()
				paymentFuture.get(30, TimeUnit.SECONDS) to holdFuture.get(30, TimeUnit.SECONDS)
			} finally {
				pool.shutdownNow()
			}

			val status = assertIs<PaymentResult.Processed>(payment).status
			if (status == ReservationStatus.CONFIRMED) {
				assertEquals(listOf(1, 1), confirmedCounts())
				assertEquals(listOf(0, 0), heldCounts())
				assertIs<HoldResult.SoldOut>(newHold)
			} else {
				assertEquals(ReservationStatus.REFUNDED, status)
				assertEquals(listOf(0, 0), confirmedCounts())
				assertEquals(listOf(1, 1), heldCounts())
				assertIs<HoldResult.Held>(newHold)
				assertEquals(listOf("pay-$n"), refundRequestPaymentIds())
			}
		}
	}

	@Test
	fun `같은 결제가 다른 예약으로 오면 충돌로 거부하고 재고와 예약은 그대로`() {
		stock(total = 2)
		val first = hold("req-1")
		val second = hold("req-2")
		payments.handleSuccess("pay-1", first)

		assertIs<PaymentResult.PaymentConflict>(payments.handleSuccess("pay-1", second))

		assertEquals("HOLD", statusOf(second))
		assertEquals(listOf(1, 1), confirmedCounts())
		assertEquals(listOf(1, 1), heldCounts())
		assertEquals(emptyList(), refundRequestPaymentIds())
	}

	@Test
	fun `같은 결제가 서로 다른 예약으로 동시에 와도 1건만 처리`() {
		stock(total = 2)
		repeat(10) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val ids = listOf(hold("req-a-$n"), hold("req-b-$n"))
			val pool = Executors.newFixedThreadPool(2)
			val start = CountDownLatch(1)
			val results = try {
				val futures = ids.map { id ->
					pool.submit(Callable { start.await(); payments.handleSuccess("pay-$n", id) })
				}
				start.countDown()
				futures.map { it.get(30, TimeUnit.SECONDS) }
			} finally {
				pool.shutdownNow()
			}

			assertEquals(1, results.count { it is PaymentResult.Processed })
			assertEquals(1, results.count { it is PaymentResult.PaymentConflict })
			assertEquals(listOf(1, 1), confirmedCounts())
			assertEquals(listOf(1, 1), heldCounts())
		}
	}

	@Test
	fun `없는 예약의 결제는 저장하지 않음`() {
		assertIs<PaymentResult.ReservationNotFound>(payments.handleSuccess("pay-1", 999L))
		assertEquals(0, jdbc.sql("SELECT count(*) FROM payment_event").query(Int::class.java).single())
	}

	@Test
	fun `만료 작업과 결제 성공이 동시에 실행돼도 재고가 어긋나지 않음`() {
		stock()
		repeat(10) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val id = hold("req-$n")
			clock.advance(Duration.ofMinutes(10))
			val pool = Executors.newFixedThreadPool(2)
			val start = CountDownLatch(1)
			try {
				val futures = listOf(
					pool.submit { start.await(); reservations.expireDueHolds() },
					pool.submit { start.await(); payments.handleSuccess("pay-$n", id) },
				)
				start.countDown()
				futures.forEach { it.get(30, TimeUnit.SECONDS) }
			} finally {
				pool.shutdownNow()
			}

			assertEquals("CONFIRMED", statusOf(id))
			assertEquals(listOf(0, 0), heldCounts())
			assertEquals(listOf(1, 1), confirmedCounts())
		}
	}
}
