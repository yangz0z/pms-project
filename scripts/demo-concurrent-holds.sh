#!/usr/bin/env bash
# 마지막 객실에 20건을 동시에 신청한 뒤 결제, 중복 알림, 매진 응답을 차례로 확인
# 재실행 전 재고 초기화: docker compose down -v && docker compose up -d --build
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8989}"
RUN_ID="$(date +%s)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

hold_body() {
  printf '{"requestKey":"%s","roomTypeId":1,"checkIn":"2030-11-10","checkOut":"2030-11-12"}' "$1"
}

post() {
  curl -sS -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$2"
}

echo "== 예약 신청 20건 동시 전송"
for n in $(seq 1 20); do
  curl -sS -o "$WORK_DIR/$n.json" -w '%{http_code}\n' -X POST "$BASE_URL/reservations" \
    -H 'Content-Type: application/json' -d "$(hold_body "demo-$RUN_ID-$n")" > "$WORK_DIR/$n.code" &
done
wait

created=$(cat "$WORK_DIR"/*.code | grep -c '^201$' || true)
held=$(cat "$WORK_DIR"/*.json 2>/dev/null | grep -o 'TEMPORARILY_HELD' | wc -l | tr -d ' ' || true)
echo "HOLD ${created}건, TEMPORARILY_HELD ${held}건"

if [ "$created" -eq 0 ]; then
  echo "점유에 성공한 신청이 없습니다. 서버가 실행 중인지, 재고를 초기화했는지 확인하세요." >&2
  echo "초기화: docker compose down -v && docker compose up -d --build" >&2
  exit 1
fi

winner="$(grep -l '^201$' "$WORK_DIR"/*.code | head -1)"
reservation_id="$(sed -E 's/.*"id":([0-9]+).*/\1/' "${winner%.code}.json")"
payment="{\"paymentId\":\"pay-$RUN_ID\",\"reservationId\":$reservation_id}"

echo "== 결제 성공 알림"
post /payments/callback "$payment"; echo
echo "== 같은 결제 성공 알림 재수신"
post /payments/callback "$payment"; echo
echo "== 확정 후 다른 고객 신청"
post /reservations "$(hold_body "demo-$RUN_ID-late")"; echo
