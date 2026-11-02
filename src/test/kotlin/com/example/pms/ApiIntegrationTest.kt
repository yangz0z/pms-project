package com.example.pms

import com.example.pms.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
class ApiIntegrationTest : IntegrationTest() {

	@Autowired
	private lateinit var mvc: MockMvc

	private fun holdJson(key: String, from: String = "2030-11-10", until: String = "2030-11-12") =
		"""{"requestKey":"$key","roomTypeId":1,"checkIn":"$from","checkOut":"$until"}"""

	private fun postHold(body: String) = mvc.post("/reservations") {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun postPayment(paymentId: String, reservationId: Long) = mvc.post("/payments/callback") {
		contentType = MediaType.APPLICATION_JSON
		content = """{"paymentId":"$paymentId","reservationId":$reservationId}"""
	}

	@Test
	fun `신규 신청은 201, 재전송은 200으로 같은 예약 반환`() {
		stock()

		postHold(holdJson("req-1")).andExpect {
			status { isCreated() }
			jsonPath("$.id") { value(1) }
			jsonPath("$.status") { value("HOLD") }
			jsonPath("$.version") { value(1) }
			jsonPath("$.holdExpiresAt") { value("2030-01-01T00:10:00Z") }
		}
		postHold(holdJson("req-1")).andExpect {
			status { isOk() }
			jsonPath("$.id") { value(1) }
		}
	}

	@Test
	fun `점유 중이면 409 TEMPORARILY_HELD와 Retry-After 반환`() {
		stock()
		postHold(holdJson("req-1"))

		postHold(holdJson("req-2")).andExpect {
			status { isConflict() }
			header { string("Retry-After", "600") }
			jsonPath("$.code") { value("TEMPORARILY_HELD") }
		}
	}

	@Test
	fun `확정으로 만실이면 409 SOLD_OUT 반환`() {
		stock()
		postHold(holdJson("req-1"))
		postPayment("pay-1", 1L)

		postHold(holdJson("req-2")).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("SOLD_OUT") }
		}
	}

	@Test
	fun `결제 성공 응답과 중복 수신 표시`() {
		stock()
		postHold(holdJson("req-1"))

		postPayment("pay-1", 1L).andExpect {
			status { isOk() }
			jsonPath("$.status") { value("CONFIRMED") }
			jsonPath("$.duplicate") { value(false) }
		}
		postPayment("pay-1", 1L).andExpect {
			status { isOk() }
			jsonPath("$.duplicate") { value(true) }
		}
	}

	@Test
	fun `같은 결제가 다른 예약으로 오면 409 PAYMENT_CONFLICT`() {
		stock(until = checkOut.plusDays(2))
		postHold(holdJson("req-1"))
		postHold(holdJson("req-2", from = "2030-11-12", until = "2030-11-13"))
		postPayment("pay-1", 1L)

		postPayment("pay-1", 2L).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("PAYMENT_CONFLICT") }
		}
	}

	@Test
	fun `없는 예약의 결제는 404`() {
		postPayment("pay-1", 999L).andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
		}
	}

	@Test
	fun `잘못된 입력은 400`() {
		postHold(holdJson("req-1", from = "2030-11-12", until = "2030-11-10")).andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("INVALID_REQUEST") }
		}
		postHold(holdJson(" ")).andExpect { status { isBadRequest() } }
		postHold(holdJson("req\\u0000nul")).andExpect { status { isBadRequest() } }
		postHold("""{"requestKey":"req-1"}""").andExpect { status { isBadRequest() } }
		postHold(holdJson("req-1", from = "2029-12-31", until = "2030-01-02")).andExpect { status { isBadRequest() } }
		postHold(holdJson("req-1", from = "2030-01-01", until = "2030-01-02")).andExpect { status { isBadRequest() } }
		postHold(holdJson("req-1", from = "2030-02-01", until = "2030-03-04")).andExpect { status { isBadRequest() } }
		postHold(holdJson("req-1", from = "+999999999-01-01", until = "+999999999-01-02")).andExpect {
			status { isBadRequest() }
		}
	}

	private fun postDateChange(
		reservationId: Long,
		key: String,
		from: String,
		until: String,
		version: Int = 1,
	) = mvc.post("/reservations/$reservationId/date-change") {
		contentType = MediaType.APPLICATION_JSON
		content = """{"requestKey":"$key","version":$version,"checkIn":"$from","checkOut":"$until"}"""
	}

	@Test
	fun `같은 requestKey를 다른 기간으로 보내면 409`() {
		stock(until = checkOut.plusDays(1))
		postHold(holdJson("req-1"))

		postHold(holdJson("req-1", until = "2030-11-13")).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("REQUEST_KEY_CONFLICT") }
		}
	}

	@Test
	fun `날짜 변경 성공은 바뀐 기간을 반환`() {
		stock(from = checkIn.minusDays(1))
		postHold(holdJson("req-1"))

		postDateChange(1L, "req-1", "2030-11-09", "2030-11-11").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(1) }
			jsonPath("$.status") { value("HOLD") }
			jsonPath("$.version") { value(2) }
			jsonPath("$.checkIn") { value("2030-11-09") }
			jsonPath("$.checkOut") { value("2030-11-11") }
		}
	}

	@Test
	fun `날짜 변경 실패 응답`() {
		stock(from = checkIn.minusDays(1), until = checkOut.plusDays(1))
		postHold(holdJson("req-1"))
		postHold(holdJson("req-2", from = "2030-11-09", until = "2030-11-10"))

		postDateChange(1L, "req-1", "2030-11-09", "2030-11-11").andExpect {
			status { isConflict() }
			header { exists("Retry-After") }
			jsonPath("$.code") { value("TEMPORARILY_HELD") }
		}
		postDateChange(1L, "req-1", "2030-11-20", "2030-11-21").andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("SOLD_OUT") }
		}
		postDateChange(1L, "wrong", "2030-11-10", "2030-11-13").andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
		}
		postDateChange(1L, "req-1", "2030-11-13", "2030-11-12").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `버전이 현재 예약과 다르면 409 RESERVATION_CHANGED, 누락되면 400`() {
		stock(from = checkIn.minusDays(1), until = checkOut.plusDays(1))
		postHold(holdJson("req-1"))

		postDateChange(1L, "req-1", "2030-11-09", "2030-11-11", version = 2).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("RESERVATION_CHANGED") }
		}
		mvc.post("/reservations/1/date-change") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"requestKey":"req-1","checkIn":"2030-11-09","checkOut":"2030-11-11"}"""
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `만료된 예약의 날짜 변경은 409 NOT_CHANGEABLE`() {
		stock()
		postHold(holdJson("req-1"))
		clock.advance(java.time.Duration.ofMinutes(10))

		postDateChange(1L, "req-1", "2030-11-10", "2030-11-11").andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("NOT_CHANGEABLE") }
		}
	}

	@Test
	fun `체크인이 지난 예약의 날짜 변경은 409 NOT_CHANGEABLE, 지난 날짜로 변경은 400`() {
		stock(from = checkIn.minusDays(1), until = checkOut.plusDays(1))
		postHold(holdJson("req-1"))
		postPayment("pay-1", 1L)

		clock.set(checkIn.minusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
		postDateChange(1L, "req-1", "2030-11-09", "2030-11-11").andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("INVALID_REQUEST") }
		}

		clock.set(checkIn.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
		postDateChange(1L, "req-1", "2030-11-11", "2030-11-13").andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("NOT_CHANGEABLE") }
		}
	}

	@Test
	fun `운영자는 상태별 환불 요청을 조회`() {
		stock(total = 2)
		postHold(holdJson("req-1"))
		postPayment("pay-1", 1L)
		postPayment("pay-2", 1L)

		mvc.get("/ops/refund-requests?status=PENDING").andExpect {
			status { isOk() }
			jsonPath("$[0].paymentId") { value("pay-2") }
			jsonPath("$[0].reservationId") { value(1) }
			jsonPath("$[0].status") { value("PENDING") }
			jsonPath("$[0].attempts") { value(0) }
		}
		mvc.get("/ops/refund-requests?status=NEEDS_REVIEW").andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(0) }
		}
	}

	@Test
	fun `환불 요청 조회의 상태가 잘못되면 400`() {
		mvc.get("/ops/refund-requests?status=UNKNOWN").andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("INVALID_REQUEST") }
		}
		mvc.get("/ops/refund-requests").andExpect { status { isBadRequest() } }
	}
}
