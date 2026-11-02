package com.example.pms.reservation

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["pms.hold.sweep-enabled"], havingValue = "true", matchIfMissing = true)
class HoldSweepScheduler(private val reservationService: ReservationService) {

	@Scheduled(fixedDelayString = "\${pms.hold.sweep-interval}")
	fun sweep() {
		reservationService.expireDueHolds()
	}
}
