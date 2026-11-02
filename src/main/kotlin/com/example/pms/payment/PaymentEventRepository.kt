package com.example.pms.payment

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

interface PaymentEventRepository {

	/**
	 * @return 처음 수신한 결제면 true, 이미 처리한 결제면 false
	 */
	fun saveIfNew(paymentId: String, reservationId: Long, receivedAt: Instant): Boolean
}

@Repository
class JdbcPaymentEventRepository(private val jdbc: JdbcClient) : PaymentEventRepository {

	override fun saveIfNew(paymentId: String, reservationId: Long, receivedAt: Instant): Boolean =
		jdbc.sql(
			"""
			INSERT INTO payment_event (payment_id, reservation_id, received_at)
			VALUES (:paymentId, :reservationId, :receivedAt)
			ON CONFLICT (payment_id) DO NOTHING
			""".trimIndent(),
		)
			.param("paymentId", paymentId)
			.param("reservationId", reservationId)
			.param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
			.update() == 1
}
