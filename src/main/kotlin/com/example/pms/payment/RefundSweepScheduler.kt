package com.example.pms.payment

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["pms.refund.sweep-enabled"], havingValue = "true", matchIfMissing = true)
class RefundSweepScheduler(private val processor: RefundProcessor) {

	@Scheduled(fixedDelayString = "\${pms.refund.sweep-interval}")
	fun sweep() {
		processor.processDue()
	}
}
