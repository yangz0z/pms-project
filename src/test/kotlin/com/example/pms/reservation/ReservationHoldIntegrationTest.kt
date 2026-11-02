package com.example.pms.reservation

import com.example.pms.inventory.InvalidStayPeriodException
import com.example.pms.inventory.StayPeriod
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReservationHoldIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var reservations: ReservationService

	@Autowired
	private lateinit var changes: ReservationDateChangeService

	private fun command(key: String, from: LocalDate = checkIn, until: LocalDate = checkOut) =
		HoldCommand(key, 1L, StayPeriod(from, until))

	private fun <T> runConcurrently(tasks: List<() -> T>): List<T> {
		val pool = Executors.newFixedThreadPool(tasks.size)
		val ready = CountDownLatch(tasks.size)
		val start = CountDownLatch(1)
		try {
			val futures = tasks.map { task ->
				pool.submit(Callable {
					ready.countDown()
					start.await()
					task()
				})
			}
			ready.await()
			start.countDown()
			return futures.map { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `마지막 객실에 20건이 동시에 신청하면 1건만 점유`() {
		stock()

		val results = runConcurrently((1..20).map { n -> { reservations.hold(command("req-$n")) } })

		assertEquals(1, results.count { it is HoldResult.Held })
		assertEquals(19, results.count { it is HoldResult.TemporarilyHeld })
		assertEquals(listOf(1, 1), heldCounts())
		assertEquals(1, jdbc.sql("SELECT count(*) FROM reservation").query(Int::class.java).single())
	}

	@Test
	fun `겹치는 연박 요청이 동시에 와도 교착 상태 없이 재고를 초과하지 않음`() {
		val day1 = LocalDate.of(2030, 11, 10)
		stock(from = day1, until = day1.plusDays(4), total = 2)

		val tasks: List<() -> HoldResult> = (1..30).map { n ->
			val from = day1.plusDays((n % 3).toLong())
			val task: () -> HoldResult = { reservations.hold(command("req-$n", from, from.plusDays(2))) }
			task
		}
		val results = runConcurrently(tasks)

		val heldReservations = results.count { it is HoldResult.Held }
		assertTrue(heldReservations > 0)
		// 예약마다 2박을 점유하므로 합계가 다르면 동시 갱신 중 값이 유실된 것
		assertEquals(heldReservations * 2, heldCounts().sum())
	}

	@Test
	fun `같은 요청 키를 동시에 재전송해도 예약은 1건`() {
		stock(total = 5)

		val results = runConcurrently((1..10).map { { reservations.hold(command("req-same")) } })

		assertTrue(results.all { it is HoldResult.Held })
		assertEquals(1, results.count { !(it as HoldResult.Held).replayed })
		assertEquals(listOf(1, 1), heldCounts())
	}

	@Test
	fun `점유가 있으면 가장 빠른 만료까지 남은 시간을 재시도 시간으로 반환`() {
		stock()
		reservations.hold(command("req-1"))
		clock.advance(Duration.ofMinutes(4))

		val result = assertIs<HoldResult.TemporarilyHeld>(reservations.hold(command("req-2")))

		assertEquals(360L, result.retryAfterSeconds)
	}

	@Test
	fun `체크인 날짜가 된 뒤 도착한 재전송은 기존 예약을 반환`() {
		stock()
		reservations.hold(command("req-1"))
		clock.set(checkIn.atStartOfDay().toInstant(ZoneOffset.UTC))

		val result = assertIs<HoldResult.Held>(reservations.hold(command("req-1")))

		assertTrue(result.replayed)
	}

	@Test
	fun `같은 키를 다른 기간으로 보내면 충돌로 거부하고 예약은 그대로`() {
		stock(from = checkIn, until = checkOut.plusDays(2))
		reservations.hold(command("req-1"))

		assertIs<HoldResult.RequestKeyConflict>(reservations.hold(command("req-1", checkIn, checkOut.plusDays(1))))

		assertEquals(listOf(1, 1, 0, 0), heldCounts())
	}

	@Test
	fun `같은 키가 다른 기간으로 동시에 와도 예약은 1건이고 나머지는 충돌`() {
		stock(from = checkIn, until = checkIn.plusDays(12), total = 3)

		val results = runConcurrently(
			(1..5).map { n -> { reservations.hold(command("req-same", checkIn.plusDays(n * 2L), checkIn.plusDays(n * 2L + 1))) } },
		)

		assertEquals(1, results.count { it is HoldResult.Held })
		assertEquals(4, results.count { it is HoldResult.RequestKeyConflict })
		assertEquals(1, heldCounts().sum())
	}

	@Test
	fun `날짜를 변경한 뒤 처음 신청이 재전송되면 변경된 예약을 반환`() {
		stock(from = checkIn.minusDays(1), until = checkOut)
		val held = assertIs<HoldResult.Held>(reservations.hold(command("req-1")))
		val moved = StayPeriod(checkIn.minusDays(1), checkOut.minusDays(1))
		changes.change(DateChangeCommand(held.reservation.id, "req-1", held.reservation.version, moved))

		val replayed = assertIs<HoldResult.Held>(reservations.hold(command("req-1")))

		assertTrue(replayed.replayed)
		assertEquals(moved, replayed.reservation.period)
		assertIs<HoldResult.RequestKeyConflict>(reservations.hold(command("req-1", moved.checkIn, moved.checkOut)))
	}

	@Test
	fun `체크인이 당일이면 거부`() {
		stock()
		clock.set(checkIn.atStartOfDay().toInstant(ZoneOffset.UTC))

		assertFailsWith<InvalidStayPeriodException> { reservations.hold(command("req-1")) }
	}

	@Test
	fun `체크인이 다음 날이면 허용`() {
		stock()
		clock.set(checkIn.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))

		assertIs<HoldResult.Held>(reservations.hold(command("req-1")))
	}

	@Test
	fun `하루라도 확정으로 만실이면 매진`() {
		stock()
		jdbc.sql("UPDATE room_inventory SET confirmed = 1 WHERE stay_date = :date").param("date", checkIn.plusDays(1)).update()

		assertIs<HoldResult.SoldOut>(reservations.hold(command("req-1")))
		assertEquals(listOf(0, 0), heldCounts())
	}

	@Test
	fun `실패한 신청은 저장하지 않아 같은 키로 재시도 가능`() {
		stock()
		val first = assertIs<HoldResult.Held>(reservations.hold(command("req-1")))
		assertFalse(first.replayed)
		assertIs<HoldResult.TemporarilyHeld>(reservations.hold(command("req-2")))
		clock.advance(Duration.ofMinutes(10))
		reservations.expireDueHolds()

		assertIs<HoldResult.Held>(reservations.hold(command("req-2")))
	}
}
