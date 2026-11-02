CREATE TABLE room_inventory (
    room_type_id BIGINT NOT NULL,
    stay_date    DATE   NOT NULL,
    total        INT    NOT NULL CHECK (total >= 0),
    confirmed    INT    NOT NULL DEFAULT 0 CHECK (confirmed >= 0),
    held         INT    NOT NULL DEFAULT 0 CHECK (held >= 0),
    PRIMARY KEY (room_type_id, stay_date),
    -- 애플리케이션 결함이 있어도 초과 판매가 저장되지 않도록 하는 마지막 방어선
    CHECK (confirmed + held <= total)
);

CREATE TABLE reservation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_key     VARCHAR(100) NOT NULL UNIQUE,
    room_type_id    BIGINT       NOT NULL,
    check_in        DATE         NOT NULL,
    check_out       DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('HOLD', 'CONFIRMED', 'EXPIRED', 'REFUNDED')),
    hold_expires_at TIMESTAMPTZ  NOT NULL,
    CHECK (check_in < check_out)
);

CREATE INDEX reservation_active_hold_idx ON reservation (hold_expires_at) WHERE status = 'HOLD';

CREATE TABLE payment_event (
    payment_id     VARCHAR(100) PRIMARY KEY,
    reservation_id BIGINT       NOT NULL REFERENCES reservation (id),
    received_at    TIMESTAMPTZ  NOT NULL
);
