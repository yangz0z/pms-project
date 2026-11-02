-- 날짜 변경 후에도 처음 신청의 재전송과 다른 신청을 구분하기 위해 요청 기간을 별도 보관
ALTER TABLE reservation
    ADD COLUMN requested_check_in  DATE,
    ADD COLUMN requested_check_out DATE;

UPDATE reservation SET requested_check_in = check_in, requested_check_out = check_out;

ALTER TABLE reservation
    ALTER COLUMN requested_check_in SET NOT NULL,
    ALTER COLUMN requested_check_out SET NOT NULL;
