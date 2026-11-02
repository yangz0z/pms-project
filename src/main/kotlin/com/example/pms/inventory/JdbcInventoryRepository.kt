package com.example.pms.inventory

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class JdbcInventoryRepository(private val jdbc: JdbcClient) : InventoryRepository {

	private val rowMapper = RowMapper { rs, _ ->
		DailyInventory(
			roomTypeId = rs.getLong("room_type_id"),
			stayDate = rs.getObject("stay_date", LocalDate::class.java),
			total = rs.getInt("total"),
			confirmed = rs.getInt("confirmed"),
			held = rs.getInt("held"),
		)
	}

	override fun findAndLock(roomTypeId: Long, period: StayPeriod): StayInventory {
		val days = jdbc.sql(
			"""
			SELECT room_type_id, stay_date, total, confirmed, held
			FROM room_inventory
			WHERE room_type_id = :roomTypeId AND stay_date >= :checkIn AND stay_date < :checkOut
			ORDER BY stay_date
			FOR UPDATE
			""".trimIndent(),
		)
			.param("roomTypeId", roomTypeId)
			.param("checkIn", period.checkIn)
			.param("checkOut", period.checkOut)
			.query(rowMapper)
			.list()
		return StayInventory(period, days)
	}

	override fun findAndLockNights(roomTypeId: Long, nights: List<LocalDate>): List<DailyInventory> =
		jdbc.sql(
			"""
			SELECT room_type_id, stay_date, total, confirmed, held
			FROM room_inventory
			WHERE room_type_id = :roomTypeId AND stay_date IN (:nights)
			ORDER BY stay_date
			FOR UPDATE
			""".trimIndent(),
		)
			.param("roomTypeId", roomTypeId)
			.param("nights", nights)
			.query(rowMapper)
			.list()

	override fun save(inventory: StayInventory) = save(inventory.days)

	override fun save(days: List<DailyInventory>) {
		days.forEach { day ->
			jdbc.sql(
				"""
				UPDATE room_inventory
				SET confirmed = :confirmed, held = :held
				WHERE room_type_id = :roomTypeId AND stay_date = :stayDate
				""".trimIndent(),
			)
				.param("confirmed", day.confirmed)
				.param("held", day.held)
				.param("roomTypeId", day.roomTypeId)
				.param("stayDate", day.stayDate)
				.update()
		}
	}
}
