package com.example.pms.reservation

import com.example.pms.inventory.InvalidStayPeriodException
import com.example.pms.inventory.StayPeriod
import com.example.pms.payment.PaymentResult
import com.example.pms.payment.PaymentService
import com.example.pms.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReservationDateChangeIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var reservations: ReservationService

	@Autowired
	private lateinit var changes: ReservationDateChangeService

	@Autowired
	private lateinit var payments: PaymentService

	private fun nov(day: Int): LocalDate = LocalDate.of(2030, 11, day)

	private fun period(from: Int, until: Int) = StayPeriod(nov(from), nov(until))

	private fun stockNovember(total: Int = 1) = stock(nov(1), nov(30), total)

	private fun hold(key: String, from: Int = 10, until: Int = 12): Reservation =
		assertIs<HoldResult.Held>(reservations.hold(HoldCommand(key, 1L, period(from, until)))).reservation

	private fun change(reservation: Reservation, from: Int, until: Int, key: String = reservation.requestKey) =
		changes.change(DateChangeCommand(reservation.id, key, storedVersion(reservation.id), period(from, until)))

	private fun inventory(day: Int): Pair<Int, Int> =
		jdbc.sql("SELECT confirmed, held FROM room_inventory WHERE stay_date = :date")
			.param("date", nov(day))
			.query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
			.single()

	private fun storedPeriod(id: Long): StayPeriod =
		jdbc.sql("SELECT check_in, check_out FROM reservation WHERE id = :id")
			.param("id", id)
			.query { rs, _ ->
				StayPeriod(rs.getObject(1, LocalDate::class.java), rs.getObject(2, LocalDate::class.java))
			}
			.single()

	private fun storedVersion(id: Long): Int =
		jdbc.sql("SELECT version FROM reservation WHERE id = :id")
			.param("id", id)
			.query { rs, _ -> rs.getInt(1) }
			.single()

	private fun <R> runConcurrently(tasks: List<() -> R>): List<R> {
		val pool = Executors.newFixedThreadPool(tasks.size)
		val start = CountDownLatch(1)
		try {
			val futures = tasks.map { task -> pool.submit(Callable { start.await(); task() }) }
			start.countDown()
			return futures.map { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `앞당기면 추가 날짜만 점유하고 빠지는 날짜만 해제`() {
		stockNovember()
		val reservation = hold("req-1")

		val changed = assertIs<DateChangeResult.Changed>(change(reservation, 9, 11))

		assertEquals(period(9, 11), changed.reservation.period)
		assertEquals(period(9, 11), storedPeriod(reservation.id))
		assertEquals(listOf(0 to 1, 0 to 1, 0 to 0), listOf(inventory(9), inventory(10), inventory(11)))
	}

	@Test
	fun `연장과 단축과 겹치지 않는 이동을 같은 규칙으로 처리`() {
		stockNovember()
		val reservation = hold("req-1")

		assertIs<DateChangeResult.Changed>(change(reservation, 10, 13))
		assertEquals(listOf(0 to 1, 0 to 1, 0 to 1), listOf(inventory(10), inventory(11), inventory(12)))

		assertIs<DateChangeResult.Changed>(change(reservation, 10, 11))
		assertEquals(listOf(0 to 1, 0 to 0, 0 to 0), listOf(inventory(10), inventory(11), inventory(12)))

		assertIs<DateChangeResult.Changed>(change(reservation, 20, 22))
		assertEquals(listOf(0 to 0, 0 to 1, 0 to 1), listOf(inventory(10), inventory(20), inventory(21)))
	}

	@Test
	fun `확정 예약은 확정 수량을 이동`() {
		stockNovember()
		val reservation = hold("req-1")
		payments.handleSuccess("pay-1", reservation.id)

		val changed = assertIs<DateChangeResult.Changed>(change(reservation, 9, 11))

		assertEquals(ReservationStatus.CONFIRMED, changed.reservation.status)
		assertEquals(listOf(1 to 0, 1 to 0, 0 to 0), listOf(inventory(9), inventory(10), inventory(11)))
	}

	@Test
	fun `추가 날짜를 다른 고객이 점유 중이면 변경만 실패하고 기존 예약 유지`() {
		stockNovember()
		val reservation = hold("req-1")
		hold("req-2", 9, 10)

		assertIs<DateChangeResult.TemporarilyHeld>(change(reservation, 9, 11))

		assertEquals(period(10, 12), storedPeriod(reservation.id))
		assertEquals(listOf(0 to 1, 0 to 1, 0 to 1), listOf(inventory(9), inventory(10), inventory(11)))
	}

	@Test
	fun `추가 날짜가 확정 만실이거나 판매하지 않으면 매진으로 실패하고 기존 예약 유지`() {
		stock(nov(9), nov(13))
		val reservation = hold("req-1")
		payments.handleSuccess("pay-2", hold("req-2", 9, 10).id)

		assertIs<DateChangeResult.SoldOut>(change(reservation, 9, 11))
		assertIs<DateChangeResult.SoldOut>(change(reservation, 10, 14))

		assertEquals(period(10, 12), storedPeriod(reservation.id))
		assertEquals(0 to 1, inventory(11))
	}

	@Test
	fun `겹치는 날짜를 본인이 점유 중이어도 변경 가능`() {
		stockNovember()
		val reservation = hold("req-1")

		assertIs<DateChangeResult.Changed>(change(reservation, 11, 13))
	}

	@Test
	fun `같은 날짜로 재전송하면 변경 없이 성공`() {
		stockNovember()
		val reservation = hold("req-1")
		change(reservation, 9, 11)

		assertIs<DateChangeResult.Changed>(change(reservation, 9, 11))

		assertEquals(listOf(0 to 1, 0 to 1, 0 to 0), listOf(inventory(9), inventory(10), inventory(11)))
	}

	@Test
	fun `지연된 이전 변경 요청은 최신 날짜를 되돌리지 않음`() {
		stockNovember()
		val reservation = hold("req-1")
		change(reservation, 20, 22)

		val late = DateChangeCommand(reservation.id, "req-1", 1, period(9, 11))
		assertIs<DateChangeResult.ReservationChanged>(changes.change(late))

		assertEquals(period(20, 22), storedPeriod(reservation.id))
		assertEquals(listOf(0 to 0, 0 to 1), listOf(inventory(9), inventory(20)))
	}

	@Test
	fun `날짜를 원래대로 되돌린 뒤 도착한 지연 요청도 거부`() {
		stockNovember()
		val reservation = hold("req-1")
		change(reservation, 20, 22)
		change(reservation, 10, 12)

		val late = DateChangeCommand(reservation.id, "req-1", 1, period(9, 11))
		assertIs<DateChangeResult.ReservationChanged>(changes.change(late))

		assertEquals(period(10, 12), storedPeriod(reservation.id))
		assertEquals(3, storedVersion(reservation.id))
		assertEquals(0 to 0, inventory(9))
	}

	@Test
	fun `성공한 변경만 버전을 올림`() {
		stockNovember()
		val reservation = hold("req-1")
		hold("req-2", 9, 10)

		change(reservation, 9, 11)
		assertEquals(1, storedVersion(reservation.id))

		change(reservation, 10, 12)
		assertEquals(1, storedVersion(reservation.id))

		val changed = assertIs<DateChangeResult.Changed>(change(reservation, 10, 13))
		assertEquals(2, changed.reservation.version)
		assertEquals(2, storedVersion(reservation.id))

		payments.handleSuccess("pay-1", reservation.id)
		assertEquals(2, storedVersion(reservation.id))
	}

	@Test
	fun `이미 반영된 변경의 재전송은 버전이 달라도 성공하고 버전을 올리지 않음`() {
		stockNovember()
		val reservation = hold("req-1")
		change(reservation, 9, 11)

		val replay = DateChangeCommand(reservation.id, "req-1", 1, period(9, 11))

		assertIs<DateChangeResult.Changed>(changes.change(replay))
		assertEquals(2, storedVersion(reservation.id))
	}

	@Test
	fun `변경해도 점유 만료 시각은 유지`() {
		stockNovember()
		val reservation = hold("req-1")
		clock.advance(Duration.ofMinutes(5))

		val changed = assertIs<DateChangeResult.Changed>(change(reservation, 9, 11))

		assertEquals(reservation.holdExpiresAt, changed.reservation.holdExpiresAt)
	}

	@Test
	fun `requestKey가 다르면 없는 예약으로 처리`() {
		stockNovember()
		val reservation = hold("req-1")

		assertIs<DateChangeResult.ReservationNotFound>(change(reservation, 9, 11, key = "someone-else"))

		assertEquals(period(10, 12), storedPeriod(reservation.id))
	}

	@Test
	fun `만료된 예약은 변경 불가, 만료 시각이 지난 점유도 변경 불가`() {
		stockNovember()
		val reservation = hold("req-1")
		clock.advance(Duration.ofMinutes(10))

		assertIs<DateChangeResult.NotChangeable>(change(reservation, 9, 11))

		reservations.expireDueHolds()
		assertIs<DateChangeResult.NotChangeable>(change(reservation, 9, 11))
		assertEquals(listOf(0 to 0, 0 to 0), listOf(inventory(9), inventory(10)))
	}

	@Test
	fun `새 체크인이 오늘 이전이면 입력 오류, 기존 체크인이 오늘 이전이면 변경 불가`() {
		stockNovember()
		val reservation = hold("req-1")
		payments.handleSuccess("pay-1", reservation.id)

		clock.set(nov(9).atStartOfDay().toInstant(ZoneOffset.UTC))
		assertFailsWith<InvalidStayPeriodException> { change(reservation, 9, 11) }

		clock.set(nov(10).atStartOfDay().toInstant(ZoneOffset.UTC))
		assertIs<DateChangeResult.NotChangeable>(change(reservation, 11, 13))
		assertIs<DateChangeResult.Changed>(change(reservation, 10, 12))

		assertEquals(period(10, 12), storedPeriod(reservation.id))
	}

	@Test
	fun `변경 후 결제와 만료는 바뀐 날짜의 재고에 반영`() {
		stockNovember()
		val paid = hold("req-1", 10, 12)
		change(paid, 9, 11)
		payments.handleSuccess("pay-1", paid.id)
		assertEquals(listOf(1 to 0, 1 to 0, 0 to 0), listOf(inventory(9), inventory(10), inventory(11)))

		val expired = hold("req-2", 20, 22)
		change(expired, 21, 23)
		clock.advance(Duration.ofMinutes(10))
		reservations.expireDueHolds()
		assertEquals(listOf(0 to 0, 0 to 0, 0 to 0), listOf(inventory(20), inventory(21), inventory(22)))
	}

	@Test
	fun `재시도 시간은 본인 점유를 제외하고 계산`() {
		stockNovember()
		val mine = hold("req-1", 10, 12)
		clock.advance(Duration.ofMinutes(4))
		hold("req-2", 12, 13)

		val result = assertIs<DateChangeResult.TemporarilyHeld>(change(mine, 11, 13))

		assertEquals(600L, result.retryAfterSeconds)
	}

	@Test
	fun `서로 상대 날짜로 동시에 옮겨도 교착 상태 없이 처리`() {
		stockNovember(total = 2)
		repeat(20) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val a = hold("req-a-$n", 9, 10)
			val b = hold("req-b-$n", 20, 21)

			val results = runConcurrently(listOf({ change(a, 20, 21) }, { change(b, 9, 10) }))

			assertTrue(results.all { it is DateChangeResult.Changed })
			assertEquals(listOf(0 to 1, 0 to 1), listOf(inventory(9), inventory(20)))
		}
	}

	@Test
	fun `마지막 객실로 동시에 변경하면 1건만 성공`() {
		stockNovember()
		val requests = (1..10).map { hold("req-$it", it, it + 1) }

		val results = runConcurrently(requests.map { reservation -> { change(reservation, 20, 21) } })

		assertEquals(1, results.count { it is DateChangeResult.Changed })
		assertEquals(9, results.count { it is DateChangeResult.TemporarilyHeld })
		assertEquals(0 to 1, inventory(20))
	}

	@Test
	fun `변경과 새 신청이 동시에 와도 재고를 초과하지 않음`() {
		stockNovember()
		repeat(10) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val reservation = hold("req-a-$n", 10, 11)

			val (changed, held) = runConcurrently(
				listOf<() -> Any>(
					{ change(reservation, 20, 21) },
					{ reservations.hold(HoldCommand("req-b-$n", 1L, period(20, 21))) },
				),
			)

			assertEquals(changed is DateChangeResult.Changed, held !is HoldResult.Held)
			assertEquals(0 to 1, inventory(20))
		}
	}

	@Test
	fun `변경과 결제가 동시에 와도 재고가 어긋나지 않음`() {
		stockNovember()
		repeat(10) { n ->
			resetBookings()
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val reservation = hold("req-$n")

			val (changed, paid) = runConcurrently(
				listOf<() -> Any>(
					{ change(reservation, 9, 11) },
					{ payments.handleSuccess("pay-$n", reservation.id) },
				),
			)

			assertIs<DateChangeResult.Changed>(changed)
			assertEquals(ReservationStatus.CONFIRMED, assertIs<PaymentResult.Processed>(paid).status)
			assertEquals(listOf(1 to 0, 1 to 0, 0 to 0), listOf(inventory(9), inventory(10), inventory(11)))
		}
	}

	@Test
	fun `변경과 만료가 동시에 와도 재고가 어긋나지 않음`() {
		stockNovember()
		repeat(10) { n ->
			resetBookings()
			clock.set(com.example.pms.support.START)
			jdbc.sql("UPDATE room_inventory SET held = 0, confirmed = 0").update()
			val reservation = hold("req-$n")
			clock.advance(Duration.ofMinutes(10).minusNanos(1))

			runConcurrently(
				listOf<() -> Unit>(
					{ change(reservation, 9, 11) },
					{
						clock.advance(Duration.ofNanos(1))
						reservations.expireDueHolds()
					},
				),
			)

			val status = statusOf(reservation.id)
			val total = listOf(9, 10, 11).sumOf { inventory(it).second }
			assertEquals(if (status == "HOLD") 2 else 0, total)
			assertNotEquals("CONFIRMED", status)
		}
	}
}
