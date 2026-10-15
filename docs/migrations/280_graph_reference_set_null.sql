-- 이슈 #280: 훈련 기록이 남은 층의 도면 재업로드·초기화 시 FK 위반(500) 수정
--
-- ddl-auto: update는 이미 있는 FK의 ON DELETE 동작과 NOT NULL을 바꾸지 않으므로 운영 DB에 직접 실행한다.
--
-- 실행 순서: 이 SQL을 먼저 실행한 뒤 #280 코드를 배포한다.
--   - 기존 코드는 trigger_edge_id에 항상 값을 넣고, 새 컬럼(floor_id/floor_num)을 쓰지 않으므로 이 SQL이 먼저
--     적용돼도 그대로 동작한다.
--   - floor_id/floor_num 채우기(1단계)는 트리거 엣지가 아직 남아 있을 때만 가능하다. 배포 후 누군가 도면을
--     재업로드하면 그 층의 엣지가 지워져 해당 이력의 층 정보를 다시 채울 수 없다.
--   - 여러 번 실행해도 결과가 같다(IF NOT EXISTS, floor_id IS NULL 조건, 같은 이름으로 FK 재생성).
--
-- 실행 방법: 트랜잭션 안에서 실행하고, 마지막 확인 쿼리 결과를 본 뒤 COMMIT (이상하면 ROLLBACK).

BEGIN;

-- 1. 재계산 이력에 층 정보 컬럼 추가 후, 아직 남아 있는 트리거 엣지에서 채운다.
ALTER TABLE route_recalculations ADD COLUMN IF NOT EXISTS floor_id uuid;
ALTER TABLE route_recalculations ADD COLUMN IF NOT EXISTS floor_num integer;

UPDATE route_recalculations r
SET floor_id = e.floor_id,
    floor_num = f.floor_num
FROM map_edges e
JOIN floors f ON f.id = e.floor_id
WHERE r.trigger_edge_id = e.id
  AND r.floor_id IS NULL;

-- 2. 엣지가 지워지면 trigger_edge_id를 null로 둘 수 있도록 NOT NULL 해제.
ALTER TABLE route_recalculations ALTER COLUMN trigger_edge_id DROP NOT NULL;

-- 3. 그래프를 참조하는 FK 6개를 ON DELETE SET NULL로 다시 만든다.
--    FK 이름은 Hibernate가 생성한 해시라 환경마다 같다는 보장이 없어, 테이블·컬럼으로 찾아서 같은 이름으로 재생성한다.
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT c.conname, c.conrelid::regclass AS tbl, a.attname AS col, c.confrelid::regclass AS ref_tbl
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f'
          AND array_length(c.conkey, 1) = 1
          AND (
              (c.conrelid = 'route_recalculations'::regclass AND a.attname = 'trigger_edge_id')
              OR (c.conrelid = 'training_scenarios'::regclass AND a.attname = 'start_node_id')
              OR (c.conrelid = 'iot_lights'::regclass
                  AND a.attname IN ('decision_node_id', 'left_edge_id', 'right_edge_id', 'cctv_id'))
          )
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.tbl, fk.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %s (id) ON DELETE SET NULL',
                       fk.tbl, fk.conname, fk.col, fk.ref_tbl);
    END LOOP;
END $$;

-- 4. 확인: 아래 6개 행이 모두 "ON DELETE SET NULL"로 끝나야 한다.
SELECT c.conrelid::regclass AS table_name, a.attname AS column_name, pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
WHERE c.contype = 'f'
  AND (
      (c.conrelid = 'route_recalculations'::regclass AND a.attname = 'trigger_edge_id')
      OR (c.conrelid = 'training_scenarios'::regclass AND a.attname = 'start_node_id')
      OR (c.conrelid = 'iot_lights'::regclass
          AND a.attname IN ('decision_node_id', 'left_edge_id', 'right_edge_id', 'cctv_id'))
  )
ORDER BY 1, 2;

-- 확인: 층 정보를 채우지 못한 이력 수 (트리거 엣지가 이미 없는 옛 행. 0이 아니어도 동작에는 문제없고 층 표시만 비어 있다)
SELECT count(*) AS recalculations_without_floor FROM route_recalculations WHERE floor_id IS NULL;

-- 결과가 맞으면 COMMIT; 아니면 ROLLBACK;
