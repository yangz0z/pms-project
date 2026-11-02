CREATE TABLE refund_request (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id      VARCHAR(100) NOT NULL UNIQUE REFERENCES payment_event (payment_id),
    reservation_id  BIGINT       NOT NULL REFERENCES reservation (id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'NEEDS_REVIEW')),
    attempts        INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500)
);

CREATE INDEX refund_request_due_idx ON refund_request (next_attempt_at) WHERE status = 'PENDING';
