-- Aeronova Airlines: schema built from the Airport Management System ER diagram (MySQL 8)
-- Added beyond the ER diagram: AIRPORT table, FLIGHT.Base_Fare, AIRCRAFT.Registration, Password_Hash on PASSENGER and STAFF.
-- All DATETIME values are stored in UTC.

DROP DATABASE IF EXISTS aeronova;
CREATE DATABASE aeronova CHARACTER SET utf8mb4;
USE aeronova;

CREATE TABLE AIRLINE (
  Airline_ID   INT AUTO_INCREMENT PRIMARY KEY,
  Airline_Name VARCHAR(100) NOT NULL,
  Airline_Code CHAR(2) NOT NULL UNIQUE,
  Country      VARCHAR(60)  NOT NULL
);

CREATE TABLE AIRCRAFT (                                   -- AIRLINE 1 --operates--> N AIRCRAFT
  Aircraft_ID INT AUTO_INCREMENT PRIMARY KEY,
  Airline_ID  INT NOT NULL,
  Model       VARCHAR(60) NOT NULL,
  Capacity    INT NOT NULL CHECK (Capacity > 0),
  Registration VARCHAR(12) NOT NULL UNIQUE,
  FOREIGN KEY (Airline_ID) REFERENCES AIRLINE(Airline_ID)
);

CREATE TABLE AIRPORT (
  Airport_Code    CHAR(3) PRIMARY KEY,
  Airport_Name    VARCHAR(100) NOT NULL,
  City            VARCHAR(60)  NOT NULL,
  Country         VARCHAR(60)  NOT NULL,
  Country_Code    CHAR(2)      NOT NULL,
  Route_No        INT NOT NULL UNIQUE,
  Duration_Min    INT NOT NULL,
  Base_Fare       DECIMAL(10,2) NOT NULL,
  Flights_Per_Day TINYINT NOT NULL DEFAULT 1,
  Via             CHAR(3) NULL,
  Featured        TINYINT(1) NOT NULL DEFAULT 0
);

CREATE TABLE FLIGHT (                                     -- AIRCRAFT 1 --assigned to--> N FLIGHT
  Flight_ID      INT AUTO_INCREMENT PRIMARY KEY,
  Aircraft_ID    INT NOT NULL,
  Flight_Number  VARCHAR(10) NOT NULL,
  Departure      CHAR(3) NOT NULL,                        -- IATA airport code
  Destination    CHAR(3) NOT NULL,
  Departure_Time DATETIME NOT NULL,
  Arrival_Time   DATETIME NOT NULL,
  Status         ENUM('Scheduled','Delayed','Boarding','Departed','Arrived','Cancelled') NOT NULL DEFAULT 'Scheduled',
  Base_Fare      DECIMAL(10,2) NOT NULL,                  -- added: economy fare in BDT
  FOREIGN KEY (Aircraft_ID) REFERENCES AIRCRAFT(Aircraft_ID),
  FOREIGN KEY (Departure)   REFERENCES AIRPORT(Airport_Code),
  FOREIGN KEY (Destination) REFERENCES AIRPORT(Airport_Code),
  UNIQUE KEY uq_flight (Flight_Number, Departure_Time),
  INDEX idx_route (Departure, Destination, Departure_Time),
  INDEX idx_number (Flight_Number)
);

CREATE TABLE PASSENGER (
  Passenger_ID INT AUTO_INCREMENT PRIMARY KEY,
  Name         VARCHAR(100) NOT NULL,
  Passport_No  VARCHAR(20)  NOT NULL UNIQUE,
  Gender       ENUM('Male','Female','Other'),
  Nationality  VARCHAR(60),
  Phone        VARCHAR(25),
  Email        VARCHAR(120) UNIQUE,
  Password_Hash VARCHAR(200)
);

CREATE TABLE BOOKING (                                    -- PASSENGER 1 --makes--> N BOOKING; FLIGHT 1 --has--> N BOOKING
  Booking_ID     INT AUTO_INCREMENT PRIMARY KEY,
  Passenger_ID   INT NOT NULL,
  Flight_ID      INT NOT NULL,
  Booking_Date   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  Booking_Status ENUM('Pending','Confirmed','Cancelled') NOT NULL DEFAULT 'Pending',
  Group_Ref      VARCHAR(12) NULL,                        -- v5: same value on both flights of a round trip
  Leg            VARCHAR(10) NULL,                        -- v5: 'Outbound' or 'Return'
  FOREIGN KEY (Passenger_ID) REFERENCES PASSENGER(Passenger_ID),
  FOREIGN KEY (Flight_ID)    REFERENCES FLIGHT(Flight_ID)
);

CREATE TABLE TICKET (                                     -- BOOKING 1 --> N TICKET (passenger is reached through the booking)
  Ticket_ID     INT AUTO_INCREMENT PRIMARY KEY,
  Booking_ID    INT NOT NULL,
  Seat_Number   VARCHAR(5),
  Class         ENUM('Economy','Premium','Royal Suite') NOT NULL DEFAULT 'Economy',
  Passenger_Type ENUM('Adult','Child') NOT NULL DEFAULT 'Adult',
  Pax_Name       VARCHAR(100) NULL,                       -- v5: the traveller's own name, date of birth and passport
  Pax_DOB        DATE NULL,
  Pax_Passport   VARCHAR(20) NULL,
  Extra_Bag_Kg   INT NOT NULL DEFAULT 0,                  -- v6 add-ons
  Meal           VARCHAR(20) NULL,
  Insurance      TINYINT(1) NOT NULL DEFAULT 0,
  Ticket_Status ENUM('Issued','Used','Cancelled') NOT NULL DEFAULT 'Issued',
  FOREIGN KEY (Booking_ID) REFERENCES BOOKING(Booking_ID)
);

CREATE TABLE PAYMENT (                                    -- BOOKING 1 --paid through--> N PAYMENT
  Payment_ID     INT AUTO_INCREMENT PRIMARY KEY,
  Booking_ID     INT NOT NULL,
  Amount         DECIMAL(10,2) NOT NULL,
  Payment_Method ENUM('Card','bKash','Nagad','Bank Transfer','Cash') NOT NULL,
  Payment_Date   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  Payment_Status ENUM('Pending','Paid','Failed','Refunded') NOT NULL DEFAULT 'Pending',
  FOREIGN KEY (Booking_ID) REFERENCES BOOKING(Booking_ID)
);

CREATE TABLE STAFF (
  Staff_ID   INT AUTO_INCREMENT PRIMARY KEY,
  Name       VARCHAR(100) NOT NULL,
  Position   VARCHAR(60),
  Department VARCHAR(60),
  Phone      VARCHAR(25),
  Email      VARCHAR(120),
  Password_Hash VARCHAR(200)
);

CREATE TABLE CHECK_IN (                                   -- TICKET 1 --used for--> 1 CHECK_IN
  Checkin_ID   INT AUTO_INCREMENT PRIMARY KEY,
  Ticket_ID    INT NOT NULL UNIQUE,
  Passenger_ID INT NOT NULL,
  Staff_ID     INT NULL,                                  -- STAFF 1 --handled by--> N CHECK_IN (empty for online check-in)
  CheckIn_Time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  Status       ENUM('Checked In','Boarded','No Show') NOT NULL DEFAULT 'Checked In',
  FOREIGN KEY (Ticket_ID)    REFERENCES TICKET(Ticket_ID),
  FOREIGN KEY (Passenger_ID) REFERENCES PASSENGER(Passenger_ID),
  FOREIGN KEY (Staff_ID)     REFERENCES STAFF(Staff_ID)
);

CREATE TABLE SECURITY_CHECK (                             -- PASSENGER 1 --undergoes--> N; STAFF 1 --handled by--> N
  Security_ID  INT AUTO_INCREMENT PRIMARY KEY,
  Passenger_ID INT NOT NULL,
  Staff_ID     INT NOT NULL,
  Check_Time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  Result       ENUM('Cleared','Flagged','Denied') NOT NULL,
  Remarks      VARCHAR(255),
  FOREIGN KEY (Passenger_ID) REFERENCES PASSENGER(Passenger_ID),
  FOREIGN KEY (Staff_ID)     REFERENCES STAFF(Staff_ID)
);

CREATE TABLE IMMIGRATION (                                -- PASSENGER 1 --undergoes--> N; STAFF 1 --handles--> N
  Immigration_ID INT AUTO_INCREMENT PRIMARY KEY,
  Passenger_ID   INT NOT NULL,
  Staff_ID       INT NOT NULL,
  Flight_ID      INT NOT NULL,
  Check_Time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  Visa_Status    ENUM('Valid','Expired','Not Required','Missing') NOT NULL,
  Result         ENUM('Approved','Rejected','Pending') NOT NULL DEFAULT 'Pending',
  FOREIGN KEY (Passenger_ID) REFERENCES PASSENGER(Passenger_ID),
  FOREIGN KEY (Staff_ID)     REFERENCES STAFF(Staff_ID),
  FOREIGN KEY (Flight_ID)    REFERENCES FLIGHT(Flight_ID)
);

CREATE TABLE LOUNGE (
  Lounge_ID   INT AUTO_INCREMENT PRIMARY KEY,
  Lounge_Name VARCHAR(80) NOT NULL,
  Location    VARCHAR(100),
  Capacity    INT,
  Type        VARCHAR(40)
);

CREATE TABLE LOUNGE_ACCESS (                              -- PASSENGER 1 --accesses--> N; LOUNGE 1 --for--> N
  Access_ID    INT AUTO_INCREMENT PRIMARY KEY,
  Passenger_ID INT NOT NULL,
  Lounge_ID    INT NOT NULL,
  Entry_Time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (Passenger_ID) REFERENCES PASSENGER(Passenger_ID),
  FOREIGN KEY (Lounge_ID)    REFERENCES LOUNGE(Lounge_ID)
);

-- ---------- Seed data ----------
INSERT INTO AIRLINE (Airline_Name, Airline_Code, Country) VALUES ('Aeronova Airlines','AN','Bangladesh');

INSERT INTO AIRPORT (Airport_Code, Airport_Name, City, Country, Country_Code, Route_No, Duration_Min, Base_Fare, Flights_Per_Day, Via, Featured) VALUES
('DAC','Hazrat Shahjalal International Airport','Dhaka','Bangladesh','BD',0,0,0,0,NULL,0),
('CGP','Shah Amanat International Airport','Chattogram','Bangladesh','BD',1,50,5200,3,NULL,0),
('CXB','Cox''s Bazar Airport','Cox''s Bazar','Bangladesh','BD',2,70,6500,2,NULL,0),
('ZYL','Osmani International Airport','Sylhet','Bangladesh','BD',3,55,5400,2,NULL,0),
('JSR','Jashore Airport','Jashore','Bangladesh','BD',4,50,4800,2,NULL,0),
('RJH','Shah Makhdum Airport','Rajshahi','Bangladesh','BD',5,55,5000,2,NULL,0),
('SPD','Saidpur Airport','Saidpur','Bangladesh','BD',6,65,5600,2,NULL,0),
('BZL','Barishal Airport','Barishal','Bangladesh','BD',7,45,4600,2,NULL,0),
('CCU','Netaji Subhas Chandra Bose International Airport','Kolkata','India','IN',8,60,12500,2,NULL,0),
('DEL','Indira Gandhi International Airport','Delhi','India','IN',9,150,19500,1,NULL,0),
('BOM','Chhatrapati Shivaji Maharaj International Airport','Mumbai','India','IN',10,180,22500,1,NULL,0),
('MAA','Chennai International Airport','Chennai','India','IN',11,150,21000,1,NULL,0),
('BLR','Kempegowda International Airport','Bengaluru','India','IN',12,190,23500,1,NULL,0),
('HYD','Rajiv Gandhi International Airport','Hyderabad','India','IN',13,170,22500,1,NULL,0),
('KTM','Tribhuvan International Airport','Kathmandu','Nepal','NP',14,120,18500,1,NULL,0),
('PBH','Paro International Airport','Paro','Bhutan','BT',15,105,24000,1,NULL,0),
('CMB','Bandaranaike International Airport','Colombo','Sri Lanka','LK',16,190,28500,1,NULL,0),
('MLE','Velana International Airport','Male','Maldives','MV',17,235,32000,1,NULL,0),
('SIN','Changi Airport','Singapore','Singapore','SG',18,255,36500,2,NULL,1),
('KUL','Kuala Lumpur International Airport','Kuala Lumpur','Malaysia','MY',19,235,30500,2,NULL,0),
('BKK','Suvarnabhumi Airport','Bangkok','Thailand','TH',20,180,29500,2,NULL,0),
('DMK','Don Mueang International Airport','Bangkok','Thailand','TH',21,185,27500,1,NULL,0),
('HKT','Phuket International Airport','Phuket','Thailand','TH',22,205,31500,1,NULL,0),
('CGK','Soekarno-Hatta International Airport','Jakarta','Indonesia','ID',23,310,38500,1,NULL,0),
('DPS','Ngurah Rai International Airport','Bali (Denpasar)','Indonesia','ID',24,380,44500,1,NULL,0),
('MNL','Ninoy Aquino International Airport','Manila','Philippines','PH',25,330,41500,1,NULL,0),
('SGN','Tan Son Nhat International Airport','Ho Chi Minh City','Vietnam','VN',26,230,33500,1,NULL,0),
('HAN','Noi Bai International Airport','Hanoi','Vietnam','VN',27,205,32500,1,NULL,0),
('HKG','Hong Kong International Airport','Hong Kong','Hong Kong','HK',28,250,38500,1,NULL,0),
('PVG','Shanghai Pudong International Airport','Shanghai','China','CN',29,330,42500,1,NULL,0),
('PEK','Beijing Capital International Airport','Beijing','China','CN',30,375,46500,1,NULL,0),
('CAN','Guangzhou Baiyun International Airport','Guangzhou','China','CN',31,245,36500,1,NULL,0),
('KMG','Kunming Changshui International Airport','Kunming','China','CN',32,150,27500,1,NULL,0),
('ICN','Incheon International Airport','Seoul','South Korea','KR',33,400,55500,1,NULL,0),
('NRT','Narita International Airport','Tokyo','Japan','JP',34,470,62500,1,NULL,0),
('HND','Haneda Airport','Tokyo','Japan','JP',35,475,64500,1,NULL,1),
('KIX','Kansai International Airport','Osaka','Japan','JP',36,450,60500,1,NULL,0),
('DXB','Dubai International Airport','Dubai','United Arab Emirates','AE',37,265,34500,3,NULL,1),
('AUH','Zayed International Airport','Abu Dhabi','United Arab Emirates','AE',38,270,33500,1,NULL,0),
('SHJ','Sharjah International Airport','Sharjah','United Arab Emirates','AE',39,265,31500,1,NULL,0),
('DOH','Hamad International Airport','Doha','Qatar','QA',40,285,35500,2,NULL,0),
('KWI','Kuwait International Airport','Kuwait City','Kuwait','KW',41,290,36500,1,NULL,0),
('BAH','Bahrain International Airport','Manama','Bahrain','BH',42,300,37500,1,NULL,0),
('MCT','Muscat International Airport','Muscat','Oman','OM',43,265,34500,1,NULL,0),
('RUH','King Khalid International Airport','Riyadh','Saudi Arabia','SA',44,330,41500,1,NULL,0),
('JED','King Abdulaziz International Airport','Jeddah','Saudi Arabia','SA',45,380,43500,2,NULL,0),
('MED','Prince Mohammad bin Abdulaziz International Airport','Madinah','Saudi Arabia','SA',46,385,43500,1,NULL,0),
('DMM','King Fahd International Airport','Dammam','Saudi Arabia','SA',47,335,41500,1,NULL,0),
('AMM','Queen Alia International Airport','Amman','Jordan','JO',48,400,50500,1,NULL,0),
('IST','Istanbul Airport','Istanbul','Turkey','TR',49,520,62500,2,NULL,1),
('SAW','Sabiha Gokcen International Airport','Istanbul','Turkey','TR',50,525,60500,1,NULL,0),
('CAI','Cairo International Airport','Cairo','Egypt','EG',51,540,58500,1,NULL,0),
('LHR','Heathrow Airport','London','United Kingdom','GB',52,600,85500,3,NULL,1),
('LGW','Gatwick Airport','London','United Kingdom','GB',53,605,80500,1,NULL,0),
('MAN','Manchester Airport','Manchester','United Kingdom','GB',54,610,79500,1,NULL,0),
('BHX','Birmingham Airport','Birmingham','United Kingdom','GB',55,615,78500,1,NULL,0),
('CDG','Charles de Gaulle Airport','Paris','France','FR',56,620,84500,1,NULL,0),
('FRA','Frankfurt Airport','Frankfurt','Germany','DE',57,610,82500,1,NULL,0),
('MUC','Munich Airport','Munich','Germany','DE',58,620,81500,1,NULL,0),
('AMS','Amsterdam Schiphol Airport','Amsterdam','Netherlands','NL',59,620,83500,1,NULL,0),
('ZRH','Zurich Airport','Zurich','Switzerland','CH',60,625,86500,1,NULL,0),
('FCO','Leonardo da Vinci-Fiumicino Airport','Rome','Italy','IT',61,640,78500,1,NULL,0),
('MXP','Milan Malpensa Airport','Milan','Italy','IT',62,630,79500,1,NULL,0),
('MAD','Adolfo Suarez Madrid-Barajas Airport','Madrid','Spain','ES',63,690,83500,1,NULL,0),
('BCN','Barcelona-El Prat Airport','Barcelona','Spain','ES',64,680,81500,1,NULL,0),
('VIE','Vienna International Airport','Vienna','Austria','AT',65,610,80500,1,NULL,0),
('CPH','Copenhagen Airport','Copenhagen','Denmark','DK',66,630,84500,1,NULL,0),
('ARN','Stockholm Arlanda Airport','Stockholm','Sweden','SE',67,640,83500,1,NULL,0),
('OSL','Oslo Gardermoen Airport','Oslo','Norway','NO',68,645,84500,1,NULL,0),
('DUB','Dublin Airport','Dublin','Ireland','IE',69,660,86500,1,NULL,0),
('ATH','Athens International Airport','Athens','Greece','GR',70,560,72500,1,NULL,0),
('LIS','Lisbon Humberto Delgado Airport','Lisbon','Portugal','PT',71,720,87500,1,NULL,0),
('BRU','Brussels Airport','Brussels','Belgium','BE',72,630,82500,1,NULL,0),
('JFK','John F. Kennedy International Airport','New York','United States','US',73,1180,112500,1,'DOH',1),
('EWR','Newark Liberty International Airport','Newark','United States','US',74,1190,108500,1,'DOH',0),
('LAX','Los Angeles International Airport','Los Angeles','United States','US',75,1380,125500,1,'DOH',0),
('ORD','O''Hare International Airport','Chicago','United States','US',76,1250,110500,1,'DOH',0),
('IAD','Washington Dulles International Airport','Washington D.C.','United States','US',77,1170,106500,1,'DOH',0),
('DFW','Dallas Fort Worth International Airport','Dallas','United States','US',78,1330,118500,1,'DOH',0),
('SFO','San Francisco International Airport','San Francisco','United States','US',79,1400,128500,1,'DOH',0),
('YYZ','Toronto Pearson International Airport','Toronto','Canada','CA',80,1200,105500,1,'DOH',0),
('YVR','Vancouver International Airport','Vancouver','Canada','CA',81,1350,118500,1,'DOH',0),
('YUL','Montreal-Trudeau International Airport','Montreal','Canada','CA',82,1210,104500,1,'DOH',0),
('SYD','Sydney Kingsford Smith Airport','Sydney','Australia','AU',83,960,92500,1,'SIN',0),
('MEL','Melbourne Airport','Melbourne','Australia','AU',84,970,90500,1,'SIN',0),
('AKL','Auckland Airport','Auckland','New Zealand','NZ',85,1100,108500,1,'SIN',0),
('JNB','O. R. Tambo International Airport','Johannesburg','South Africa','ZA',86,780,88500,1,'DXB',0),
('NBO','Jomo Kenyatta International Airport','Nairobi','Kenya','KE',87,620,70500,1,'DXB',0),
('ADD','Bole International Airport','Addis Ababa','Ethiopia','ET',88,700,68500,1,'DXB',0);

INSERT INTO AIRCRAFT (Airline_ID, Model, Capacity, Registration) VALUES
 (1,'Boeing 787-9 Dreamliner',290,'S2-ANA'),(1,'Boeing 787-9 Dreamliner',290,'S2-ANB'),
 (1,'Airbus A350-900',315,'S2-ANC'),(1,'Airbus A350-900',315,'S2-AND'),(1,'Boeing 777-300ER',380,'S2-ANE'),
 (1,'Airbus A321neo',190,'S2-ANF'),(1,'Airbus A321neo',190,'S2-ANG'),(1,'Boeing 737-800',174,'S2-ANH'),(1,'Boeing 737-800',174,'S2-ANJ'),
 (1,'ATR 72-600',70,'S2-ANK'),(1,'ATR 72-600',70,'S2-ANL'),(1,'De Havilland Dash 8 Q400',78,'S2-ANM');

INSERT INTO STAFF (Name, Position, Department, Phone, Email) VALUES
 ('Wasi Sadaat','Check-in Agent','Ground Services','01700000001','wasi.sadaat@aeronova.test'),
 ('Nabil Mahmud','Security Officer','Security','01700000002','nabil.mahmud@aeronova.test'),
 ('Fariba Afrin','Immigration Officer','Immigration','01700000003','fariba.afrin@aeronova.test');

INSERT INTO LOUNGE (Lounge_Name, Location, Capacity, Type) VALUES
 ('Nova Royal Lounge','Terminal 3, Level 2',120,'First'),
 ('Nova Premium Lounge','Terminal 3, Level 1',200,'Premium');

-- Flights for the next 30 days: every route has its own flight numbers (domestic AN 1xx, international AN 1xxx)
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
