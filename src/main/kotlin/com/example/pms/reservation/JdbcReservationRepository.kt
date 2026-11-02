package com.example.pms.reservation

import com.example.pms.inventory.StayPeriod
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JdbcReservationRepository(private val jdbc: JdbcClient) : ReservationRepository {

	private val rowMapper = RowMapper { rs, _ ->
		Reservation.restore(
			id = rs.getLong("id"),
			requestKey = rs.getString("request_key"),
			roomTypeId = rs.getLong("room_type_id"),
			period = StayPeriod(
				rs.getObject("check_in", LocalDate::class.java),
				rs.getObject("check_out", LocalDate::class.java),
			),
			status = ReservationStatus.valueOf(rs.getString("status")),
			holdExpiresAt = rs.getObject("hold_expires_at", OffsetDateTime::class.java).toInstant(),
		)
	}

	override fun insertIfAbsent(reservation: NewReservation): Reservation? =
		jdbc.sql(
			"""
			INSERT INTO reservation (request_key, room_type_id, check_in, check_out, status, hold_expires_at)
			VALUES (:requestKey, :roomTypeId, :checkIn, :checkOut, 'HOLD', :holdExpiresAt)
			ON CONFLICT (request_key) DO NOTHING
			RETURNING *
			""".trimIndent(),
		)
			.param("requestKey", reservation.requestKey)
			.param("roomTypeId", reservation.roomTypeId)
			.param("checkIn", reservation.period.checkIn)
			.param("checkOut", reservation.period.checkOut)
			.param("holdExpiresAt", reservation.holdExpiresAt.toOffset())
			.query(rowMapper)
			.optional()
			.orElse(null)

	override fun findByRequestKey(requestKey: String): Reservation? =
		jdbc.sql("SELECT * FROM reservation WHERE request_key = :requestKey")
			.param("requestKey", requestKey)
			.query(rowMapper)
			.optional()
			.orElse(null)

	override fun findByIdForUpdate(id: Long): Reservation? =
		jdbc.sql("SELECT * FROM reservation WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(rowMapper)
			.optional()
			.orElse(null)

	override fun updateStatus(reservation: Reservation) {
		jdbc.sql("UPDATE reservation SET status = :status WHERE id = :id")
			.param("status", reservation.status.name)
			.param("id", reservation.id)
			.update()
	}

	override fun findDueHoldIds(now: Instant, limit: Int): List<Long> =
		jdbc.sql(
			"""
			SELECT id FROM reservation
			WHERE status = 'HOLD' AND hold_expires_at <= :now
			ORDER BY hold_expires_at
			LIMIT :limit
			""".trimIndent(),
		)
			.param("now", now.toOffset())
			.param("limit", limit)
			.query { rs, _ -> rs.getLong("id") }
			.list()

	override fun findEarliestHoldExpiry(roomTypeId: Long, period: StayPeriod): Instant? =
		jdbc.sql(
			"""
			SELECT min(hold_expires_at) FROM reservation
			WHERE status = 'HOLD'
			  AND room_type_id = :roomTypeId
			  AND check_in < :checkOut
			  AND check_out > :checkIn
			""".trimIndent(),
		)
			.param("roomTypeId", roomTypeId)
			.param("checkIn", period.checkIn)
			.param("checkOut", period.checkOut)
			.query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java)?.toInstant() }
			.single()

	private fun Instant.toOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
