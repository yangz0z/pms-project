package com.example.pms.payment

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface RefundRequestRepository {

	fun enqueue(paymentId: String, reservationId: Long, now: Instant)

	/**
	 * 처리할 요청 1건을 선점하고 선점 기간만큼 다음 시도를 미룸
	 * 처리 중 서버가 멈추면 선점 기간이 지난 뒤 다시 처리
	 * @return 처리할 요청이 없으면 null
	 */
	fun claimNext(now: Instant, lease: Duration): RefundRequest?

	/**
	 * 선점 기간이 지나 다른 시도가 이어받은 뒤 도착한 결과는 무시
	 */
	fun saveResult(request: RefundRequest)

	fun findByStatus(status: RefundStatus, limit: Int): List<RefundRequest>
}

@Repository
class JdbcRefundRequestRepository(private val jdbc: JdbcClient) : RefundRequestRepository {

	private val rowMapper = RowMapper { rs, _ ->
		RefundRequest.restore(
			id = rs.getLong("id"),
			paymentId = rs.getString("payment_id"),
			reservationId = rs.getLong("reservation_id"),
			status = RefundStatus.valueOf(rs.getString("status")),
			attempts = rs.getInt("attempts"),
			nextAttemptAt = rs.getObject("next_attempt_at", OffsetDateTime::class.java).toInstant(),
			lastError = rs.getString("last_error"),
		)
	}

	override fun enqueue(paymentId: String, reservationId: Long, now: Instant) {
		jdbc.sql(
			"""
			INSERT INTO refund_request (payment_id, reservation_id, status, next_attempt_at)
			VALUES (:paymentId, :reservationId, 'PENDING', :now)
			""".trimIndent(),
		)
			.param("paymentId", paymentId)
			.param("reservationId", reservationId)
			.param("now", now.toOffset())
			.update()
	}

	override fun claimNext(now: Instant, lease: Duration): RefundRequest? =
		jdbc.sql(
			"""
			UPDATE refund_request
			SET attempts = attempts + 1, next_attempt_at = :leaseUntil
			WHERE id = (
				SELECT id FROM refund_request
				WHERE status = 'PENDING' AND next_attempt_at <= :now
				ORDER BY next_attempt_at
				LIMIT 1
				FOR UPDATE SKIP LOCKED
			)
			RETURNING *
			""".trimIndent(),
		)
			.param("now", now.toOffset())
			.param("leaseUntil", now.plus(lease).toOffset())
			.query(rowMapper)
			.optional()
			.orElse(null)

	override fun saveResult(request: RefundRequest) {
		jdbc.sql(
			"""
			UPDATE refund_request
			SET status = :status, next_attempt_at = :nextAttemptAt, last_error = :lastError
			WHERE id = :id AND status = 'PENDING' AND attempts = :attempts
			""".trimIndent(),
		)
			.param("status", request.status.name)
			.param("nextAttemptAt", request.nextAttemptAt.toOffset())
			.param("lastError", request.lastError)
			.param("id", request.id)
			.param("attempts", request.attempts)
			.update()
	}

	override fun findByStatus(status: RefundStatus, limit: Int): List<RefundRequest> =
		jdbc.sql("SELECT * FROM refund_request WHERE status = :status ORDER BY id DESC LIMIT :limit")
			.param("status", status.name)
			.param("limit", limit)
			.query(rowMapper)
			.list()

	private fun Instant.toOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
