package com.example.pms.payment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

data object RefundRequested

/**
 * 커밋 전에 호출하면 환불 후 롤백될 수 있어 커밋 직후 호출
 * 이 단계가 실패해도 저장된 요청을 주기 작업이 이어서 처리
 */
@Component
@ConditionalOnProperty(name = ["pms.refund.dispatch-enabled"], havingValue = "true", matchIfMissing = true)
class RefundDispatcher(
	private val processor: RefundProcessor,
	@Qualifier("applicationTaskExecutor") private val executor: TaskExecutor,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@TransactionalEventListener
	fun dispatch(event: RefundRequested) {
		executor.execute {
			try {
				processor.processDue()
			} catch (e: Exception) {
				log.error("환불 즉시 처리 실패", e)
			}
		}
	}
}
