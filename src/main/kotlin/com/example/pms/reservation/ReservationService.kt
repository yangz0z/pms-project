package com.example.pms.reservation

import com.example.pms.inventory.HoldAttempt
import com.example.pms.inventory.InvalidStayPeriodException
import com.example.pms.inventory.InventoryRepository
import com.example.pms.inventory.StayPeriod
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class HoldCommand(val requestKey: String, val roomTypeId: Long, val period: StayPeriod)

sealed interface HoldResult {
	data class Held(val reservation: Reservation, val replayed: Boolean) : HoldResult
	data object SoldOut : HoldResult
	data class TemporarilyHeld(val retryAfterSeconds: Long) : HoldResult
}

@Service
class ReservationService(
	private val inventories: InventoryRepository,
	private val reservations: ReservationRepository,
	private val holdExpirer: HoldExpirer,
	private val clock: Clock,
	@Value("\${pms.hold.ttl}") private val holdTtl: Duration,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun hold(command: HoldCommand): HoldResult {
		// 날짜가 바뀐 뒤 도착한 재전송도 기존 예약을 돌려주도록 날짜 검사보다 먼저 확인
		reservations.findByRequestKey(command.requestKey)?.let { return HoldResult.Held(it, replayed = true) }

		// 당일 예약을 받지 않아 체크인 날짜가 지난 뒤 결제가 확정되는 경우를 사실상 차단
		if (command.period.startsOnOrBefore(LocalDate.now(clock))) {
			throw InvalidStayPeriodException("당일 및 지난 날짜는 예약 불가")
		}

		val inventory = inventories.findAndLock(command.roomTypeId, command.period)

		// 같은 키의 동시 요청은 위 잠금에서 대기하므로 앞선 요청의 커밋 결과를 여기서 확인 가능
		reservations.findByRequestKey(command.requestKey)?.let { return HoldResult.Held(it, replayed = true) }

		return when (val attempt = inventory.tryHold()) {
			is HoldAttempt.SoldOut -> HoldResult.SoldOut
			is HoldAttempt.TemporarilyHeld -> HoldResult.TemporarilyHeld(retryAfterSeconds(command))
			is HoldAttempt.Success -> {
				val now = clock.instant()
				val created = reservations.insertIfAbsent(
					NewReservation(command.requestKey, command.roomTypeId, command.period, now.plus(holdTtl)),
				)
				if (created == null) {
					// 같은 키가 다른 기간으로 동시에 들어와 잠금이 겹치지 않은 경우
					HoldResult.Held(checkNotNull(reservations.findByRequestKey(command.requestKey)), replayed = true)
				} else {
					inventories.save(attempt.inventory)
					HoldResult.Held(created, replayed = false)
				}
			}
		}
	}

	/**
	 * 건별로 트랜잭션을 분리
	 * 한 트랜잭션에서 여러 예약을 처리하면 재고 행 잠금이 날짜순을 벗어나 교착 상태 가능
	 */
	fun expireDueHolds() {
		reservations.findDueHoldIds(clock.instant(), EXPIRE_BATCH_SIZE).forEach { id ->
			try {
				holdExpirer.expire(id)
			} catch (e: Exception) {
				log.error("점유 만료 처리 실패: reservationId={}", id, e)
			}
		}
	}

	private fun retryAfterSeconds(command: HoldCommand): Long {
		val expiry: Instant = reservations.findEarliestHoldExpiry(command.roomTypeId, command.period)
			?: return MIN_RETRY_AFTER_SECONDS
		return Duration.between(clock.instant(), expiry).seconds.coerceAtLeast(MIN_RETRY_AFTER_SECONDS)
	}

	private companion object {
		const val EXPIRE_BATCH_SIZE = 100
		const val MIN_RETRY_AFTER_SECONDS = 1L
	}
}
