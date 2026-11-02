-- 날짜를 원래대로 되돌려도 지연된 이전 변경 요청을 구분하도록 변경할 때마다 증가
ALTER TABLE reservation ADD COLUMN version INT NOT NULL DEFAULT 1 CHECK (version >= 1);
