package com.example.pms.payment

import com.example.pms.inventory.StayPeriod
import com.example.pms.reservation.HoldCommand
import com.example.pms.reservation.HoldResult
import com.example.pms.reservation.ReservationService
import com.example.pms.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RefundProcessingIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var reservations: ReservationService

	@Autowired
	private lateinit var payments: PaymentService

	@Autowired
	private lateinit var processor: RefundProcessor

	@Autowired
	private lateinit var requests: RefundRequestRepository

	@Autowired
	private lateinit var transactions: TransactionTemplate

	private fun confirmedReservation(): Long {
		stock(total = 1)
		val held = assertIs<HoldResult.Held>(reservations.hold(HoldCommand("req-1", 1L, StayPeriod(checkIn, checkOut))))
		payments.handleSuccess("pay-used", held.reservation.id)
		return held.reservation.id
	}

	private fun requestRefund(paymentId: String = "pay-refund"): Long {
		val id = confirmedReservation()
		payments.handleSuccess(paymentId, id)
		return id
	}

	private fun stateOf(paymentId: String): Triple<String, Int, String?> =
		jdbc.sql("SELECT status, attempts, last_error FROM refund_request WHERE payment_id = :id")
			.param("id", paymentId)
			.query { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getString(3)) }
			.single()

	@Test
	fun `성공하면 완료 처리하고 다시 호출하지 않음`() {
		requestRefund()

		processor.processDue()
		clock.advance(Duration.ofHours(1))
		processor.processDue()

		assertEquals(listOf("pay-refund"), refunds.refundedPaymentIds)
		assertEquals(Triple("COMPLETED", 1, null), stateOf("pay-refund"))
	}

	@Test
	fun `일시 실패하면 재시도 시각까지 기다린 뒤 다시 호출`() {
		requestRefund()
		refunds.results.add(RefundResult.Retryable("timeout"))

		processor.processDue()
		assertEquals(Triple("PENDING", 1, "timeout"), stateOf("pay-refund"))

		clock.advance(Duration.ofSeconds(9))
		processor.processDue()
		assertEquals(1, refunds.refundedPaymentIds.size)

		clock.advance(Duration.ofSeconds(1))
		processor.processDue()
		assertEquals(2, refunds.refundedPaymentIds.size)
		assertEquals("COMPLETED", stateOf("pay-refund").first)
	}

	@Test
	fun `5번 모두 실패하면 운영자 확인으로 전환하고 더 호출하지 않음`() {
		requestRefund()
		repeat(5) { refunds.results.add(RefundResult.Retryable("timeout")) }

		repeat(5) {
			processor.processDue()
			clock.advance(Duration.ofMinutes(15))
		}
		processor.processDue()

		assertEquals(5, refunds.refundedPaymentIds.size)
		assertEquals(Triple("NEEDS_REVIEW", 5, "timeout"), stateOf("pay-refund"))
	}

	@Test
	fun `결제사가 거절하면 재시도 없이 운영자 확인으로 전환`() {
		requestRefund()
		refunds.results.add(RefundResult.Rejected("already refunded"))

		processor.processDue()
		clock.advance(Duration.ofHours(1))
		processor.processDue()

		assertEquals(1, refunds.refundedPaymentIds.size)
		assertEquals(Triple("NEEDS_REVIEW", 1, "already refunded"), stateOf("pay-refund"))
	}

	@Test
	fun `호출 중 예외는 재시도하고 예외 메시지는 저장하지 않음`() {
		requestRefund()
		refunds.onRefund = {
			refunds.onRefund = {}
			throw IllegalStateException("card=1234")
		}

		processor.processDue()

		assertEquals(Triple("PENDING", 1, "IllegalStateException"), stateOf("pay-refund"))
	}

	@Test
	fun `결제사 호출 중에는 예약과 환불 요청 행을 잠그지 않음`() {
		val reservationId = requestRefund()
		var checked = false
		refunds.onRefund = {
			jdbc.sql("SELECT id FROM reservation WHERE id = :id FOR UPDATE NOWAIT").param("id", reservationId)
				.query { rs, _ -> rs.getLong(1) }.single()
			jdbc.sql("SELECT id FROM refund_request FOR UPDATE NOWAIT").query { rs, _ -> rs.getLong(1) }.single()
			checked = true
		}

		processor.processDue()

		assertEquals(true, checked)
		assertEquals("COMPLETED", stateOf("pay-refund").first)
	}

	@Test
	fun `처리 작업이 동시에 돌아도 같은 환불을 중복 호출하지 않음`() {
		val id = confirmedReservation()
		val paymentIds = (1..20).map { "pay-$it" }
		paymentIds.forEach { payments.handleSuccess(it, id) }
		val pool = Executors.newFixedThreadPool(4)
		val start = CountDownLatch(1)

		try {
			val futures = (1..4).map { pool.submit { start.await(); processor.processDue() } }
			start.countDown()
			futures.forEach { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		assertEquals(paymentIds.sorted(), refunds.refundedPaymentIds.sorted())
	}

	@Test
	fun `선점 후 서버가 멈춰도 1분 뒤 다시 처리`() {
		requestRefund()
		assertNotNull(requests.claimNext(clock.instant(), Duration.ofMinutes(1)))

		clock.advance(Duration.ofSeconds(59))
		processor.processDue()
		assertEquals(emptyList(), refunds.refundedPaymentIds)

		clock.advance(Duration.ofSeconds(1))
		processor.processDue()
		assertEquals(listOf("pay-refund"), refunds.refundedPaymentIds)
		assertEquals(Triple("COMPLETED", 2, null), stateOf("pay-refund"))
	}

	@Test
	fun `선점 기간이 지난 뒤 도착한 이전 시도의 결과는 무시`() {
		requestRefund()
		val first = assertNotNull(requests.claimNext(clock.instant(), Duration.ofMinutes(1)))
		clock.advance(Duration.ofMinutes(1))
		val second = assertNotNull(requests.claimNext(clock.instant(), Duration.ofMinutes(1)))

		requests.saveResult(first.retryLater(clock.instant(), "late failure"))

		assertEquals(Triple("PENDING", 2, null), stateOf("pay-refund"))
		requests.saveResult(second.complete())
		assertEquals("COMPLETED", stateOf("pay-refund").first)
	}

	@Test
	fun `결제 처리가 롤백되면 환불 요청도 함께 롤백`() {
		val id = confirmedReservation()

		transactions.executeWithoutResult { status ->
			payments.handleSuccess("pay-refund", id)
			status.setRollbackOnly()
		}

		assertEquals(emptyList(), refundRequestPaymentIds())
	}
}
