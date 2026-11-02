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
