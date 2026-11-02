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

	fun findReservationId(paymentId: String): Long?
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

	override fun findReservationId(paymentId: String): Long? =
		jdbc.sql("SELECT reservation_id FROM payment_event WHERE payment_id = :paymentId")
			.param("paymentId", paymentId)
			.query { rs, _ -> rs.getLong(1) }
			.optional()
			.orElse(null)
}
