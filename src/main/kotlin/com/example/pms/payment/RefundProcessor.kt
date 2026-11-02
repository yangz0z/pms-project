package com.example.pms.payment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * 결제사 호출이 잠금을 붙잡지 않도록 트랜잭션 없이 선점, 호출, 결과 기록을 나눠 실행
 */
@Component
class RefundProcessor(
	private val requests: RefundRequestRepository,
	private val gateway: RefundGateway,
	private val clock: Clock,
	@Value("\${pms.refund.lease}") private val lease: Duration,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun processDue() {
		// 호출이 느려도 뒤 건의 선점 기간이 줄지 않도록 1건씩 선점
		while (true) {
			val request = requests.claimNext(clock.instant(), lease) ?: return
			process(request)
		}
	}

	private fun process(request: RefundRequest) {
		val next = when (val result = callGateway(request)) {
			is RefundResult.Succeeded -> request.complete()
			is RefundResult.Retryable -> request.retryLater(clock.instant(), result.reason)
			is RefundResult.Rejected -> request.escalate(result.reason)
		}
		requests.saveResult(next)
		if (next.status == RefundStatus.NEEDS_REVIEW) {
			log.error("환불 운영자 확인 필요: refundRequestId={}, attempts={}", next.id, next.attempts)
		}
	}

	private fun callGateway(request: RefundRequest): RefundResult =
		try {
			gateway.refund(request.paymentId, request.reservationId)
		} catch (e: Exception) {
			// 예외 메시지에 결제 정보가 포함될 수 있어 로그와 저장값은 예외 종류로 제한
			log.warn("환불 호출 실패: refundRequestId={}, error={}", request.id, e.javaClass.simpleName)
			RefundResult.Retryable(e.javaClass.simpleName)
		}
}
