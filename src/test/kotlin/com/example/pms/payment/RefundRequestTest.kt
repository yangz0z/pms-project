package com.example.pms.payment

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefundRequestTest {

	private val now = Instant.parse("2030-01-01T00:00:00Z")

	private fun claimed(attempts: Int, status: RefundStatus = RefundStatus.PENDING) =
		RefundRequest.restore(1L, "pay-1", 1L, status, attempts, now, null)

	@Test
	fun `실패 횟수에 따라 재시도 간격을 늘림`() {
		val delays = (1..4).map { attempts ->
			Duration.between(now, claimed(attempts).retryLater(now, "timeout").nextAttemptAt)
		}

		assertEquals(
			listOf(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)),
			delays,
		)
	}

	@Test
	fun `5번째 시도까지 실패하면 운영자 확인으로 전환`() {
		val result = claimed(5).retryLater(now, "timeout")

		assertEquals(RefundStatus.NEEDS_REVIEW, result.status)
		assertEquals("timeout", result.lastError)
	}

	@Test
	fun `실패 사유는 저장 길이로 자름`() {
		assertEquals(500, claimed(1).escalate("x".repeat(600)).lastError?.length)
	}

	@Test
	fun `처리가 끝난 요청은 상태를 바꿀 수 없음`() {
		assertFailsWith<IllegalStateException> { claimed(1, RefundStatus.COMPLETED).complete() }
		assertFailsWith<IllegalStateException> { claimed(1, RefundStatus.NEEDS_REVIEW).retryLater(now, "timeout") }
		assertFailsWith<IllegalStateException> { claimed(1, RefundStatus.COMPLETED).escalate("rejected") }
	}
}
