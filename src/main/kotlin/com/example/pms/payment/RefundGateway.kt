package com.example.pms.payment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface RefundGateway {
	fun refund(paymentId: String, reservationId: Long)
}

/**
 * 실제 결제사 연동 전까지 환불 요청을 로그로만 기록
 * 실제 연동 시 외부 호출이 트랜잭션과 잠금을 붙잡지 않도록 커밋 후 처리로 변경 필요
 */
@Component
class LoggingRefundGateway : RefundGateway {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun refund(paymentId: String, reservationId: Long) {
		log.info("환불 요청: paymentId={}, reservationId={}", paymentId, reservationId)
	}
}
