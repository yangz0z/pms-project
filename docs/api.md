# API

## 예약 신청

```http
POST /reservations
Content-Type: application/json

{
  "requestKey": "req-abc",
  "roomTypeId": 1,
  "checkIn": "2030-11-10",
  "checkOut": "2030-11-12"
}
```


| 응답    | 조건                                         | 본문 또는 코드                                                                                                         |
| ----- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `201` | 새로 점유                                      | `{"id":1,"status":"HOLD","version":1,"checkIn":"2030-11-10","checkOut":"2030-11-12","holdExpiresAt":"2030-11-02T01:10:00Z"}` |
| `200` | 같은 `requestKey` 재전송                        | 기존 예약을 같은 형식으로 반환                                                                                                |
| `409` | 점유 때문에 재고 없음                               | `TEMPORARILY_HELD`, `Retry-After` 헤더 포함                                                                          |
| `409` | 확정으로 만실이거나 판매하지 않는 날짜 포함                   | `SOLD_OUT`                                                                                                       |
| `409` | 같은 `requestKey`로 다른 내용의 예약이 있음             | `REQUEST_KEY_CONFLICT`                                                                                           |
| `400` | 입력 오류, 당일 또는 지난 날짜, 체크인이 체크아웃보다 늦음, 30박 초과 | `INVALID_REQUEST`                                                                                                |


## 예약 날짜 변경

```http
POST /reservations/1/date-change
Content-Type: application/json

{
  "requestKey": "req-abc",
  "version": 1,
  "checkIn": "2030-11-09",
  "checkOut": "2030-11-11"
}
```


| 응답    | 조건                                                  | 본문 또는 코드                                |
| ----- | --------------------------------------------------- | --------------------------------------- |
| `200` | 변경 완료 또는 현재와 같은 날짜로 재전송 (체크인이 지난 예약 포함)             | 예약 신청과 같은 형식                            |
| `409` | 추가 날짜가 점유 중                                         | `TEMPORARILY_HELD`, `Retry-After` 헤더 포함 |
| `409` | 추가 날짜가 확정으로 만실이거나 판매하지 않음                           | `SOLD_OUT`                              |
| `409` | `EXPIRED`, `REFUNDED`, 만료 시각이 지난 점유, 체크인이 오늘 이전인 예약 | `NOT_CHANGEABLE`                        |
| `409` | 요청의 `version`이 현재 예약과 다름 | `RESERVATION_CHANGED` |
| `404` | 없는 예약 또는 `requestKey` 불일치                           | `RESERVATION_NOT_FOUND`                 |
| `400` | 입력 오류, 새 체크인이 오늘 이전, 30박 초과                         | `INVALID_REQUEST`                       |


## 결제 성공 알림

```http
POST /payments/callback
Content-Type: application/json

{
  "paymentId": "pay-1",
  "reservationId": 1
}
```


| 응답    | 조건                 | 본문 또는 코드                                                     |
| ----- | ------------------ | ------------------------------------------------------------ |
| `200` | 처리 완료              | `{"reservationId":1,"status":"CONFIRMED","duplicate":false}` |
| `200` | 이미 처리한 `paymentId` | 현재 상태와 `"duplicate":true`                                    |
| `409` | 같은 `paymentId`가 다른 예약으로 도착 | `PAYMENT_CONFLICT`, 재고와 예약 유지 |
| `404` | 없는 예약              | `RESERVATION_NOT_FOUND`                                      |


## 환불 요청 조회

```http
GET /ops/refund-requests?status=NEEDS_REVIEW
```

`status`는 `PENDING`, `COMPLETED`, `NEEDS_REVIEW` 중 하나이며, 최근 100건을 반환합니다.
