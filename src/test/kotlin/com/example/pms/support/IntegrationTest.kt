package com.example.pms.support

import com.example.pms.TestcontainersConfiguration
import com.example.pms.payment.RefundGateway
import com.example.pms.payment.RefundResult
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class MutableClock(private var now: Instant) : Clock() {
	override fun getZone(): ZoneId = ZoneOffset.UTC
	override fun withZone(zone: ZoneId): Clock = this
	override fun instant(): Instant = now

	fun set(instant: Instant) {
		now = instant
	}

	fun advance(duration: Duration) {
		now = now.plus(duration)
	}
}

class RecordingRefundGateway : RefundGateway {
	val refundedPaymentIds = CopyOnWriteArrayList<String>()
	val results = ConcurrentLinkedQueue<RefundResult>()

	@Volatile
	var onRefund: () -> Unit = {}

	override fun refund(paymentId: String, reservationId: Long): RefundResult {
		refundedPaymentIds.add(paymentId)
		onRefund()
		return results.poll() ?: RefundResult.Succeeded
	}

	fun reset() {
		refundedPaymentIds.clear()
		results.clear()
		onRefund = {}
	}
}

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {

	@Bean
	@Primary
	fun mutableClock() = MutableClock(START)

	@Bean
	@Primary
	fun recordingRefundGateway() = RecordingRefundGateway()
}

val START: Instant = Instant.parse("2030-01-01T00:00:00Z")

// 스케줄러가 테스트 중 시각 변경에 반응하지 않도록 비활성화하고 만료와 환불은 직접 실행
@SpringBootTest(properties = ["pms.hold.sweep-enabled=false", "pms.refund.sweep-enabled=false"])
@TestPropertySource(properties = ["pms.refund.dispatch-enabled=false"])
@Import(TestcontainersConfiguration::class, IntegrationTestConfiguration::class)
abstract class IntegrationTest {

	@Autowired
	protected lateinit var jdbc: JdbcClient

	@Autowired
	protected lateinit var clock: MutableClock

	@Autowired
	protected lateinit var refunds: RecordingRefundGateway

	protected val checkIn: LocalDate = LocalDate.of(2030, 11, 10)
	protected val checkOut: LocalDate = LocalDate.of(2030, 11, 12)

	@BeforeEach
	fun resetState() {
		resetBookings()
		jdbc.sql("TRUNCATE room_inventory").update()
		clock.set(START)
		refunds.reset()
	}

	protected fun resetBookings() {
		jdbc.sql("TRUNCATE refund_request, payment_event, reservation RESTART IDENTITY").update()
	}

	protected fun refundRequestPaymentIds(): List<String> =
		jdbc.sql("SELECT payment_id FROM refund_request ORDER BY id")
			.query { rs, _ -> rs.getString(1) }
			.list()

	protected fun stock(from: LocalDate = checkIn, until: LocalDate = checkOut, total: Int = 1, roomTypeId: Long = 1L) {
		from.datesUntil(until).forEach { date ->
			jdbc.sql("INSERT INTO room_inventory (room_type_id, stay_date, total) VALUES (:room, :date, :total)")
				.param("room", roomTypeId)
				.param("date", date)
				.param("total", total)
				.update()
		}
	}

	protected fun heldCounts(): List<Int> = counts("held")

	protected fun confirmedCounts(): List<Int> = counts("confirmed")

	private fun counts(column: String): List<Int> =
		jdbc.sql("SELECT $column FROM room_inventory ORDER BY room_type_id, stay_date")
			.query { rs, _ -> rs.getInt(1) }
			.list()

	protected fun statusOf(reservationId: Long): String =
		jdbc.sql("SELECT status FROM reservation WHERE id = :id")
			.param("id", reservationId)
			.query(String::class.java)
			.single()
}
