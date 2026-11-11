-- Run this once (phpMyAdmin > aeronova > Import). It keeps all existing bookings and accounts.
-- Gives every route 2 or 3 flights a day and adds the new flights for the next 150 days (about 5 months).
USE aeronova;

-- More flights per day: Bangladesh routes, featured and shorter routes get 3 a day, long routes 2 a day.
UPDATE AIRPORT SET Flights_Per_Day = CASE WHEN Country_Code = 'BD' OR Featured = 1 OR Duration_Min <= 420 THEN 3 ELSE 2 END WHERE Route_No > 0;

INSERT IGNORE INTO FLIGHT (Aircraft_ID, Flight_Number, Departure, Destination, Departure_Time, Arrival_Time, Status, Base_Fare)
SELECT x.ac, x.fno, x.o, x.d, x.dep, DATE_ADD(x.dep, INTERVAL x.dur MINUTE), 'Scheduled', x.fare
FROM (
  SELECT
    CASE WHEN ap.Country_Code = 'BD' THEN 10 + MOD(ap.Route_No + s.s + dr.dir + dy.n, 3)
         WHEN ap.Duration_Min >= 480 THEN 1 + MOD(ap.Route_No + s.s + dr.dir + dy.n, 5)
         ELSE 6 + MOD(ap.Route_No + s.s + dr.dir + dy.n, 4) END AS ac,
    CONCAT('AN ', IF(ap.Country_Code = 'BD', 100, 1000) + ap.Route_No * 10 + s.s * 2 + dr.dir) AS fno,
    IF(dr.dir = 0, 'DAC', ap.Airport_Code) AS o,
    IF(dr.dir = 0, ap.Airport_Code, 'DAC') AS d,
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL dy.n DAY), MAKETIME(MOD(5 + MOD(ap.Route_No * 7, 14) + s.s * 6 + dr.dir * 9, 24), MOD(ap.Route_No * 5, 60), 0)) AS dep,
    ap.Duration_Min AS dur, ap.Base_Fare AS fare
  FROM AIRPORT ap
  CROSS JOIN (SELECT a.d + b.d * 10 AS n FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14) b) dy
  CROSS JOIN (SELECT 0 AS s UNION ALL SELECT 1 UNION ALL SELECT 2) s
  CROSS JOIN (SELECT 0 AS dir UNION ALL SELECT 1) dr
  WHERE ap.Route_No > 0 AND s.s < ap.Flights_Per_Day
) x;
