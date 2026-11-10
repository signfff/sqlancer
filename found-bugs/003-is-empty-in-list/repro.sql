-- Minimal divergence:
SELECT '' IS 0          AS is_zero;        -- 0
SELECT '' IS (0 IN ())  AS is_empty_in;    -- 1
SELECT typeof(0 IN ())  AS typeof_empty_in;-- integer

-- IS NOT side:
SELECT '' IS NOT 0, '' IS NOT (0 IN ()), '' IS NOT FALSE;  -- 1|0|0

-- Adding a function to the dead side changes the semantics back:
SELECT '' IS (length('') IN ());           -- 0

-- Everywhere else it is the integer 0:
SELECT (0 IN ()) + 1, (0 IN ()) = 0, CASE WHEN (0 IN ()) THEN 1 ELSE 0 END;  -- 1|1|0

-- Agreement by luck for numeric LHS:
SELECT 0 IS (0 IN ()), 1 IS (0 IN ());     -- 1|0
