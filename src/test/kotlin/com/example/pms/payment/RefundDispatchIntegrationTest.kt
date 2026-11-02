package com.example.pms.payment

import com.example.pms.inventory.StayPeriod
import com.example.pms.reservation.HoldCommand
import com.example.pms.reservation.HoldResult
import com.example.pms.reservation.ReservationService
import com.example.pms.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestPropertySource(properties = ["pms.refund.dispatch-enabled=true"])
class RefundDispatchIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var reservations: ReservationService

	@Autowired
	private lateinit var payments: PaymentService

	@Autowired
	private lateinit var transactions: TransactionTemplate

	private fun confirmedReservation(): Long {
		stock()
		val held = assertIs<HoldResult.Held>(reservations.hold(HoldCommand("req-1", 1L, StayPeriod(checkIn, checkOut))))
		payments.handleSuccess("pay-used", held.reservation.id)
		return held.reservation.id
	}

	private fun awaitRefund(paymentId: String) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (paymentId !in refunds.refundedPaymentIds && System.nanoTime() < deadline) {
			Thread.sleep(10)
		}
		assertTrue(paymentId in refunds.refundedPaymentIds)
	}

	@Test
	fun `커밋 직후 주기 작업 없이 환불을 호출`() {
		val id = confirmedReservation()

		payments.handleSuccess("pay-refund", id)

		awaitRefund("pay-refund")
	}

	@Test
	fun `결제사 호출은 결제 알림 처리와 다른 스레드에서 실행`() {
		val id = confirmedReservation()
		val gatewayThread = AtomicReference<String>()
		refunds.onRefund = { gatewayThread.set(Thread.currentThread().name) }

		payments.handleSuccess("pay-refund", id)
		awaitRefund("pay-refund")

		assertNotEquals(Thread.currentThread().name, gatewayThread.get())
	}

	@Test
	fun `롤백된 결제는 환불을 호출하지 않음`() {
		val id = confirmedReservation()

		transactions.executeWithoutResult { status ->
			payments.handleSuccess("pay-rollback", id)
			status.setRollbackOnly()
		}
		Thread.sleep(300)

		assertEquals(emptyList(), refunds.refundedPaymentIds)
	}
}
