package com.example.pms.inventory

import java.time.LocalDate
import java.time.temporal.ChronoUnit

class InvalidStayPeriodException(message: String) : RuntimeException(message)

data class StayPeriod(val checkIn: LocalDate, val checkOut: LocalDate) {

	init {
		if (!checkIn.isBefore(checkOut)) {
			throw InvalidStayPeriodException("체크인은 체크아웃보다 빨라야 함")
		}
		if (checkOut.isAfter(LATEST_SUPPORTED_DATE)) {
			throw InvalidStayPeriodException("지원하지 않는 날짜")
		}
		if (nightCount() > MAX_NIGHTS) {
			throw InvalidStayPeriodException("최대 ${MAX_NIGHTS}박까지 예약 가능")
		}
	}

	fun nightCount(): Long = ChronoUnit.DAYS.between(checkIn, checkOut)

	fun startsOnOrBefore(date: LocalDate): Boolean = !checkIn.isAfter(date)

	private companion object {
		const val MAX_NIGHTS = 30L

		// PostgreSQL DATE 범위를 넘는 입력이 저장 단계까지 가지 않도록 차단
		val LATEST_SUPPORTED_DATE: LocalDate = LocalDate.of(9999, 12, 31)
	}
}
