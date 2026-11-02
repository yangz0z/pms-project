package com.example.pms.payment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

sealed interface RefundResult {
	data object Succeeded : RefundResult
	data class Retryable(val reason: String) : RefundResult
	data class Rejected(val reason: String) : RefundResult
}

interface RefundGateway {

	/**
	 * 같은 결제가 다시 요청될 수 있으므로 구현체는 paymentId를 멱등 키로 사용
	 * 응답 제한 시간은 pms.refund.lease보다 짧아야 함
	 */
	fun refund(paymentId: String, reservationId: Long): RefundResult
}

/**
 * 실제 결제사 연동 전까지 환불 요청을 로그로만 기록
 */
@Component
class LoggingRefundGateway : RefundGateway {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun refund(paymentId: String, reservationId: Long): RefundResult {
		log.info("환불 요청: paymentId={}, reservationId={}", paymentId, reservationId)
		return RefundResult.Succeeded
	}
}
