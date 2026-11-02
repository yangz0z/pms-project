package com.example.pms.payment

import com.example.pms.reservation.ReservationRepository
import com.example.pms.reservation.ReservationSettlement
import com.example.pms.reservation.ReservationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

sealed interface PaymentResult {
	data class Processed(val status: ReservationStatus, val duplicate: Boolean) : PaymentResult
	data object ReservationNotFound : PaymentResult
}

@Service
class PaymentService(
	private val reservations: ReservationRepository,
	private val events: PaymentEventRepository,
	private val settlement: ReservationSettlement,
	private val refunds: RefundGateway,
	private val clock: Clock,
) {

	@Transactional
	fun handleSuccess(paymentId: String, reservationId: Long): PaymentResult {
		// 이벤트 저장의 외래 키 잠금과 충돌하지 않도록 예약 행을 먼저 잠금
		val reservation = reservations.findByIdForUpdate(reservationId) ?: return PaymentResult.ReservationNotFound

		if (!events.saveIfNew(paymentId, reservationId, clock.instant())) {
			return PaymentResult.Processed(reservation.status, duplicate = true)
		}

		val settled = settlement.settle(reservation)
		// 객실 확보에 쓰이지 못한 결제는 환불. 이미 확정된 예약에 도착한 다른 결제도 포함
		val paymentUsed = reservation.awaitsPayment() && settled.status == ReservationStatus.CONFIRMED
		if (!paymentUsed) {
			refunds.refund(paymentId, reservationId)
		}
		return PaymentResult.Processed(settled.status, duplicate = false)
	}
}
