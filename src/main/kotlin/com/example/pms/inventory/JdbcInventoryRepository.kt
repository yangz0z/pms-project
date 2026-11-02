package com.example.pms.inventory

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class JdbcInventoryRepository(private val jdbc: JdbcClient) : InventoryRepository {

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
			.query { rs, _ ->
				DailyInventory(
					roomTypeId = rs.getLong("room_type_id"),
					stayDate = rs.getObject("stay_date", LocalDate::class.java),
					total = rs.getInt("total"),
					confirmed = rs.getInt("confirmed"),
					held = rs.getInt("held"),
				)
			}
			.list()
		return StayInventory(period, days)
	}

	override fun save(inventory: StayInventory) {
		inventory.days.forEach { day ->
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
