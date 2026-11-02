package com.example.pms

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	// 숙박일 판단은 서버 시간대가 아닌 숙소 현지 날짜 기준
	@Bean
	fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
