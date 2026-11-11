package com.mycompany.aeronovaairlines;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AeronovaAirlines {

    static final String DB_URL  = env("DB_URL", "jdbc:mariadb://localhost:3306/aeronova");
    static final String DB_USER = env("DB_USER", "root");
    static final String DB_PASS = env("DB_PASS", "");
    static final int    PORT    = Integer.parseInt(env("PORT", "8080"));
    static final Path   WEB     = findWeb();

    static final long   CLUB_MONTHLY = 1500, CLUB_YEARLY = 15000;   // NovaClub premium subscription price (BDT) - change here
    static final double VIP_DISCOUNT = 0.10;                        // fare discount for NovaClub members

    static final Map<String, Double> CABIN_FACTOR = Map.of("Economy", 1.0, "Premium", 1.6, "Royal Suite", 3.2);
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    record Sess(String role, long id, String name, long exp) {}
    static final Map<String, Sess> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, long[]> FAILS = new ConcurrentHashMap<>();

    interface Handler { String run(Map<String, String> params) throws Exception; }

    static Path findWeb() {
        for (String c : new String[]{"web", "../web", "aeronova/web", "../aeronova/web"}) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("index.html"))) return p;
        }
        return Path.of("web").toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        try { migrate(); } catch (Exception e) { System.out.println("Database setup problem: " + e.getMessage()); }
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/flights", ex -> api(ex, AeronovaAirlines::flights));
        server.createContext("/api/airports",   ex -> api(ex, AeronovaAirlines::airports));
        server.createContext("/api/featured",   ex -> api(ex, AeronovaAirlines::featured));
        server.createContext("/api/me",         ex -> api(ex, AeronovaAirlines::me));
        server.createContext("/api/register",   ex -> post(ex, AeronovaAirlines::register));
        server.createContext("/api/login",      ex -> post(ex, AeronovaAirlines::login));
        server.createContext("/api/logout",     ex -> post(ex, AeronovaAirlines::logout));
        server.createContext("/api/logout/all", ex -> post(ex, AeronovaAirlines::logoutAll));
        server.createContext("/api/lounges",    ex -> api(ex, AeronovaAirlines::lounges));
        server.createContext("/api/journey",    ex -> api(ex, AeronovaAirlines::journey));
        server.createContext("/api/checkin/online", ex -> post(ex, AeronovaAirlines::checkinOnline));
        server.createContext("/api/mybookings", ex -> api(ex, AeronovaAirlines::mybookings));
        server.createContext("/api/cancel",     ex -> post(ex, AeronovaAirlines::cancel));
        server.createContext("/api/club",       ex -> api(ex, AeronovaAirlines::club));
        server.createContext("/api/club/join",  ex -> post(ex, AeronovaAirlines::clubJoin));
        server.createContext("/api/reviews",    ex -> api(ex, AeronovaAirlines::reviews));
        server.createContext("/api/review",     ex -> post(ex, AeronovaAirlines::review));
        server.createContext("/api/report",     ex -> api(ex, AeronovaAirlines::report));
        server.createContext("/api/status",  ex -> api(ex, AeronovaAirlines::status));
        server.createContext("/api/booking", ex -> api(ex, AeronovaAirlines::booking));
        server.createContext("/api/meta",    ex -> api(ex, AeronovaAirlines::meta));
        server.createContext("/api/book",           ex -> post(ex, AeronovaAirlines::book));
        server.createContext("/api/seats",          ex -> api(ex, AeronovaAirlines::seatMap));
        server.createContext("/api/eticket",        ex -> api(ex, AeronovaAirlines::eticket));
        server.createContext("/api/profile",        ex -> api(ex, AeronovaAirlines::profile));
        server.createContext("/api/addons",         ex -> api(ex, AeronovaAirlines::addons));
        server.createContext("/api/profile/update", ex -> post(ex, AeronovaAirlines::profileUpdate));
        server.createContext("/api/password/change",ex -> post(ex, AeronovaAirlines::changePassword));
        server.createContext("/api/password/reset", ex -> post(ex, AeronovaAirlines::resetPassword));
        server.createContext("/api/reschedule/options", ex -> api(ex, AeronovaAirlines::rescheduleOptions));
        server.createContext("/api/reschedule",     ex -> post(ex, AeronovaAirlines::reschedule));
        server.createContext("/api/admin/flights",  ex -> api(ex, AeronovaAirlines::adminFlights));
        server.createContext("/api/admin/flight/update", ex -> post(ex, AeronovaAirlines::adminFlightUpdate));
        server.createContext("/api/admin/flight/add", ex -> post(ex, AeronovaAirlines::adminFlightAdd));
        server.createContext("/api/admin/aircraft", ex -> api(ex, AeronovaAirlines::adminAircraft));
        server.createContext("/api/admin/aircraft/add", ex -> post(ex, AeronovaAirlines::adminAircraftAdd));
        server.createContext("/api/staff/checkin",  ex -> post(ex, AeronovaAirlines::checkin));
        server.createContext("/api/staff/security", ex -> post(ex, AeronovaAirlines::security));
        server.createContext("/api/staff/immigration", ex -> post(ex, AeronovaAirlines::immigration));
        server.createContext("/api/staff/lounge",   ex -> post(ex, AeronovaAirlines::lounge));
        server.createContext("/",            AeronovaAirlines::staticFile);
        server.start();
        System.out.println("Website folder: " + WEB);
        System.out.println("Database: " + DB_URL + " as " + DB_USER);
        if (!Files.isRegularFile(WEB.resolve("index.html"))) System.out.println("WARNING: index.html was not found in that folder. Put the web folder next to src.");
        System.out.println("Aeronova is running at http://localhost:" + PORT);
    }

    static String flights(Map<String, String> p) throws Exception {
        String from = airport(p.get("origin"));
        String to = airport(p.get("destination"));
        int adults = Integer.parseInt(p.getOrDefault("adults", "1")), children = Integer.parseInt(p.getOrDefault("children", "0"));
        checkParty(adults, children);
        boolean vip = false;
        if ("passenger".equals(p.get("_role"))) { try (Connection cv = conn()) { vip = clubUntil(cv, Long.parseLong(p.get("_id"))) != null; } }
        LocalDate date = LocalDate.parse(p.getOrDefault("departDate", LocalDate.now().toString()));
        // the date the passenger picks is a calendar day at the departure airport (local time), converted to UTC for the database
        ZoneId oz = zone(from);
        LocalDateTime start = date.atStartOfDay(oz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime end = date.plusDays(1).atStartOfDay(oz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        String sql = "SELECT f.Flight_ID, f.Flight_Number, f.Departure_Time, f.Arrival_Time, f.Base_Fare, al.Airline_Name, a.Model, a.Registration, a.Capacity, "
                   + "o.Airport_Name, o.City, o.Country_Code, d.Airport_Name, d.City, d.Country_Code, COALESCE(o.Via, d.Via) "
                   + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID JOIN AIRLINE al ON al.Airline_ID = a.Airline_ID "
                   + "JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination "
                   + "WHERE f.Departure = ? AND f.Destination = ? AND f.Departure_Time >= ? AND f.Departure_Time < ? "
                   + "AND f.Departure_Time > UTC_TIMESTAMP() AND f.Status <> 'Cancelled' ORDER BY f.Departure_Time";

        List<String> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from);
            ps.setString(2, to);
            ps.setObject(3, start);
            ps.setObject(4, end);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ZonedDateTime sd = rs.getObject(3, LocalDateTime.class).atZone(ZoneOffset.UTC), sa = rs.getObject(4, LocalDateTime.class).atZone(ZoneOffset.UTC);
                    ZonedDateTime dl = sd.withZoneSameInstant(zone(from)), al = sa.withZoneSameInstant(zone(to));
                    long mins = Duration.between(sd, sa).toMinutes();
                    boolean domestic = "BD".equals(rs.getString(12)) && "BD".equals(rs.getString(15));
                    String via = rs.getString(16);
                    int fid = rs.getInt(1), cap = rs.getInt(9);
                    double base = rs.getDouble(5);
                    Map<String, SeatCabin> lay = layout(cap);
                    Map<String, Integer> sold = soldByCabin(c, fid);
                    List<String> cabins = new ArrayList<>();
                    for (String cn : CABINS) {
                        double factor = CABIN_FACTOR.get(cn);
                        long a = adultFare(base, factor, vip);
                        cabins.add(obj("name", cn, "price", a, "childPrice", childFare(a), "total", partyTotal(base, factor, vip, adults, children),
                                "seatsLeft", Math.max(0, lay.get(cn).seats().size() - sold.getOrDefault(cn, 0)), "seatsTotal", lay.get(cn).seats().size(),
                                "baggage", baggage(domestic, cn, vip), "meal", meal(domestic, cn)));
                    }
                    out.add(obj("id", fid, "no", rs.getString(2), "airline", rs.getString(6), "aircraft", rs.getString(7), "reg", rs.getString(8),
                            "date", dl.toLocalDate().toString(), "dep", dl.format(HM), "depTz", lz(sd, from), "arr", al.format(HM), "arrDate", al.toLocalDate().toString(), "arrTz", lz(sa, to),
                            "dur", (mins / 60) + "h " + (mins % 60) + "m", "stops", via == null ? "Non-stop" : "1 stop via " + via,
                            "adults", adults, "children", children, "pax", adults + children, "vip", vip, "domestic", domestic,
                            "fromCode", from, "fromCity", rs.getString(11), "toCode", to, "toCity", rs.getString(14),
                            "cabins", raw("[" + String.join(",", cabins) + "]")));
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    static String baggage(boolean domestic, String cabin, boolean vip) {
        int kg = (domestic ? 20 : 30) + ("Premium".equals(cabin) ? 10 : "Royal Suite".equals(cabin) ? 20 : 0) + (vip ? 10 : 0);
        return kg + " kg checked + 7 kg cabin";
    }

    static String meal(boolean domestic, String cabin) {
        return "Royal Suite".equals(cabin) ? "Chef-prepared dining" : domestic ? "Snack and drink" : "Hot meal and drinks";
    }

    static String airports(Map<String, String> p) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT Airport_Code, Airport_Name, City, Country, Country_Code FROM AIRPORT ORDER BY Country, City, Airport_Name")) {
            while (rs.next()) out.add(obj("code", rs.getString(1), "name", rs.getString(2), "city", rs.getString(3), "country", rs.getString(4), "domestic", "BD".equals(rs.getString(5))));
        }
        return "[" + String.join(",", out) + "]";
    }

    static String featured(Map<String, String> p) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT Airport_Code, Airport_Name, City, Country, Base_Fare FROM AIRPORT WHERE Featured = 1 ORDER BY Route_No")) {
            while (rs.next()) out.add(obj("code", rs.getString(1), "name", rs.getString(2), "city", rs.getString(3), "country", rs.getString(4), "fare", Math.round(rs.getDouble(5))));
        }
        return "[" + String.join(",", out) + "]";
    }

    // ---- passengers: adults (12+) and children (2-11, half fare) ----
    static final double CHILD_FACTOR = 0.5;   // children pay half of the adult fare
    static final int MAX_PAX = 9;             // passengers per booking

    static void checkParty(int adults, int children) {
        if (adults < 1) throw new IllegalArgumentException("At least 1 adult (age 12 or older) must travel.");
        if (children < 0) throw new IllegalArgumentException("Children cannot be negative.");
        if (adults + children > MAX_PAX) throw new IllegalArgumentException("You can book up to " + MAX_PAX + " passengers at a time.");
    }
    static long adultFare(double base, double factor, boolean vip) { return Math.round(base * factor * (vip ? 1 - VIP_DISCOUNT : 1)); }
    static long childFare(long adult) { return Math.round(adult * CHILD_FACTOR); }
    static long partyTotal(double base, double factor, boolean vip, int adults, int children) {
        long a = adultFare(base, factor, vip);
        return a * adults + childFare(a) * children;
    }

    // ---- NovaClub (premium subscription -> VIP treatment) ----
    static LocalDate clubUntil(Connection c, long passengerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT Nova_Until FROM PASSENGER WHERE Passenger_ID = ?")) {
            ps.setLong(1, passengerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getDate(1) != null) {
                    LocalDate d = rs.getDate(1).toLocalDate();
                    if (!d.isBefore(LocalDate.now())) return d;
                }
            }
        }
        return null;
    }

    static String club(Map<String, String> p) throws Exception {
        LocalDate u = null; String plan = "", no = "";
        if ("passenger".equals(p.get("_role"))) {
            long id = Long.parseLong(p.get("_id"));
            try (Connection c = conn()) {
                u = clubUntil(c, id);
                if (u != null) {
                    no = String.format("NC-%05d", id);
                    try (PreparedStatement ps = c.prepareStatement("SELECT Nova_Plan FROM PASSENGER WHERE Passenger_ID = ?")) {
                        ps.setLong(1, id);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) plan = nonNull(rs.getString(1)); }
                    }
                }
            }
        }
        return obj("member", u != null, "until", u == null ? "" : u.toString(), "plan", plan, "memberNo", no, "monthly", CLUB_MONTHLY, "yearly", CLUB_YEARLY, "discount", Math.round(VIP_DISCOUNT * 100));
    }

    static String clubJoin(Map<String, String> p) throws Exception {
        need(p, "passenger");
        long id = Long.parseLong(p.get("_id"));
        String plan = pick(p, "plan", Set.of("Monthly", "Yearly"), "Monthly");
        String method = pick(p, "method", Set.of("Card", "bKash", "Nagad", "Bank Transfer", "Cash"), "Card");   // payment is simulated
        long paid = "Yearly".equals(plan) ? CLUB_YEARLY : CLUB_MONTHLY;
        try (Connection c = conn()) {
            LocalDate cur = clubUntil(c, id);
            LocalDate from = cur != null ? cur : LocalDate.now();
            LocalDate until = "Yearly".equals(plan) ? from.plusYears(1) : from.plusMonths(1);
            exec(c, "UPDATE PASSENGER SET Nova_Until = ?, Nova_Plan = ? WHERE Passenger_ID = ?", until.toString(), plan, id);
            long receipt = insert(c, "INSERT INTO CLUB_PAYMENT (Passenger_ID, Plan_Name, Amount, Pay_Method, Valid_From, Valid_Until) VALUES (?,?,?,?,?,?)", id, plan, paid, method, from.toString(), until.toString());
            return obj("member", true, "from", from.toString(), "until", until.toString(), "plan", plan, "paid", paid, "method", method, "receipt", String.format("NCR-%06d", receipt),
                    "memberNo", String.format("NC-%05d", id), "name", nonNull(p.get("_name")), "renewed", cur != null);
        }
    }

    // ---- passenger reviews ----
    static String reviews(Map<String, String> p) throws Exception {
        List<String> out = new ArrayList<>(); double avg = 0; int n = 0;
        try (Connection c = conn(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(AVG(Rating), 0), COUNT(*) FROM REVIEW")) { rs.next(); avg = rs.getDouble(1); n = rs.getInt(2); }
            try (ResultSet rs = st.executeQuery("SELECT p.Name, r.Rating, r.Comment, r.Flight_Number, r.Created_At FROM REVIEW r JOIN PASSENGER p ON p.Passenger_ID = r.Passenger_ID ORDER BY r.Review_ID DESC LIMIT 6")) {
                while (rs.next()) {
                    String[] nm = rs.getString(1).trim().split("\\s+");
                    String shown = nm[0] + (nm.length > 1 ? " " + nm[nm.length - 1].charAt(0) + "." : "");
                    out.add(obj("name", shown, "rating", rs.getInt(2), "comment", rs.getString(3) == null ? "" : rs.getString(3),
                            "flight", rs.getString(4) == null ? "" : rs.getString(4), "date", rs.getObject(5, LocalDateTime.class).toLocalDate().toString()));
                }
            }
        }
        return "{\"avg\":" + (Math.round(avg * 10) / 10.0) + ",\"count\":" + n + ",\"reviews\":[" + String.join(",", out) + "]}";
    }

    static String review(Map<String, String> p) throws Exception {
        need(p, "passenger");
        int rating = Integer.parseInt(req(p, "rating"));
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Choose a rating from 1 to 5.");
        String comment = p.getOrDefault("comment", "").trim(); if (comment.length() > 500) comment = comment.substring(0, 500);
        String fno = p.getOrDefault("flightNo", "").trim().toUpperCase(); if (fno.length() > 12) fno = fno.substring(0, 12);
        insert("INSERT INTO REVIEW (Passenger_ID, Flight_Number, Rating, Comment) VALUES (?,?,?,?)", Long.parseLong(p.get("_id")), fno, rating, comment);
        return obj("ok", true);
    }

    // ---- flight status helpers ----
    static final Map<String, String> TZ = new HashMap<>();
    static void tz(String zone, String codes) { for (String c : codes.split(",")) TZ.put(c, zone); }
    static {
        tz("Asia/Dhaka", "DAC,CGP,CXB,ZYL,JSR,RJH,SPD,BZL"); tz("Asia/Kolkata", "CCU,DEL,BOM,MAA,BLR,HYD"); tz("Asia/Kathmandu", "KTM");
        tz("Asia/Thimphu", "PBH"); tz("Asia/Colombo", "CMB"); tz("Indian/Maldives", "MLE"); tz("Asia/Singapore", "SIN"); tz("Asia/Kuala_Lumpur", "KUL");
        tz("Asia/Bangkok", "BKK,DMK,HKT"); tz("Asia/Jakarta", "CGK"); tz("Asia/Makassar", "DPS"); tz("Asia/Manila", "MNL"); tz("Asia/Ho_Chi_Minh", "SGN,HAN");
        tz("Asia/Hong_Kong", "HKG"); tz("Asia/Shanghai", "PVG,PEK,CAN,KMG"); tz("Asia/Seoul", "ICN"); tz("Asia/Tokyo", "NRT,HND,KIX");
        tz("Asia/Dubai", "DXB,AUH,SHJ"); tz("Asia/Qatar", "DOH"); tz("Asia/Kuwait", "KWI"); tz("Asia/Bahrain", "BAH"); tz("Asia/Muscat", "MCT");
        tz("Asia/Riyadh", "RUH,JED,MED,DMM"); tz("Asia/Amman", "AMM"); tz("Europe/Istanbul", "IST,SAW"); tz("Africa/Cairo", "CAI");
        tz("Europe/London", "LHR,LGW,MAN,BHX"); tz("Europe/Paris", "CDG"); tz("Europe/Berlin", "FRA,MUC"); tz("Europe/Amsterdam", "AMS"); tz("Europe/Zurich", "ZRH");
        tz("Europe/Rome", "FCO,MXP"); tz("Europe/Madrid", "MAD,BCN"); tz("Europe/Vienna", "VIE"); tz("Europe/Copenhagen", "CPH"); tz("Europe/Stockholm", "ARN");
        tz("Europe/Oslo", "OSL"); tz("Europe/Dublin", "DUB"); tz("Europe/Athens", "ATH"); tz("Europe/Lisbon", "LIS"); tz("Europe/Brussels", "BRU");
        tz("America/New_York", "JFK,EWR,IAD"); tz("America/Los_Angeles", "LAX,SFO"); tz("America/Chicago", "ORD,DFW"); tz("America/Toronto", "YYZ,YUL"); tz("America/Vancouver", "YVR");
        tz("Australia/Sydney", "SYD"); tz("Australia/Melbourne", "MEL"); tz("Pacific/Auckland", "AKL"); tz("Africa/Johannesburg", "JNB"); tz("Africa/Nairobi", "NBO"); tz("Africa/Addis_Ababa", "ADD");
    }
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH);
    static ZoneId zone(String code) { return ZoneId.of(TZ.getOrDefault(code, "UTC")); }
    static String lt(ZonedDateTime z, String code) { return z.withZoneSameInstant(zone(code)).format(HM); }
    static String ld(ZonedDateTime z, String code) { return z.withZoneSameInstant(zone(code)).format(DAY); }
    static String lz(ZonedDateTime z, String code) { String o = z.withZoneSameInstant(zone(code)).getOffset().getId(); return "UTC" + ("Z".equals(o) ? "" : o); }
    static String dur(long mins) { return (mins / 60) + "h " + (mins % 60) + "m"; }
    // Terminal, gate and baggage belt are not stored in the database: they are generated from the flight so they stay the same every time (demo data).
    static String terminal(String code, int fid) { return "T" + (1 + Math.floorMod(code.hashCode() + fid, 3)); }
    static String gate(String code, int fid) { int h = Math.floorMod(code.hashCode() * 31 + fid * 7, 96); return "ABCD".charAt(h / 24) + "" + (1 + h % 24); }
    static int belt(String code, int fid) { return 1 + Math.floorMod(code.hashCode() + fid * 3, 8); }

    static String status(Map<String, String> p) throws Exception {
        String no = p.getOrDefault("flightNo", "").replace(" ", "").toUpperCase();
        if (no.isEmpty()) throw new IllegalArgumentException("Enter a flight number.");
        LocalDate date = LocalDate.parse(p.getOrDefault("date", LocalDate.now().toString()));
        String sql = "SELECT f.Flight_ID, f.Status, f.Departure_Time, f.Arrival_Time, f.Departure, f.Destination, o.City, o.Airport_Name, d.City, d.Airport_Name, "
                   + "a.Model, a.Registration, COALESCE(o.Via, d.Via), v.City, v.Airport_Name, o.Country_Code, d.Country_Code, f.Flight_Number "
                   + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID "
                   + "JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination "
                   + "LEFT JOIN AIRPORT v ON v.Airport_Code = COALESCE(o.Via, d.Via) "
                   + "WHERE REPLACE(f.Flight_Number, ' ', '') = ? AND f.Departure_Time >= ? ORDER BY f.Departure_Time LIMIT 1";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, no);
            ps.setObject(2, date.atStartOfDay());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("No flight found with that number.");
                int fid = rs.getInt(1); String st = rs.getString(2);
                ZonedDateTime sd = rs.getObject(3, LocalDateTime.class).atZone(ZoneOffset.UTC), sa = rs.getObject(4, LocalDateTime.class).atZone(ZoneOffset.UTC);
                String from = rs.getString(5), to = rs.getString(6);
                boolean domestic = "BD".equals(rs.getString(16)) && "BD".equals(rs.getString(17));
                int delay = "Delayed".equalsIgnoreCase(st) ? 45 : 0;
                ZonedDateTime ed = sd.plusMinutes(delay), ea = sa.plusMinutes(delay), board = ed.minusMinutes(domestic ? 40 : 60);
                Instant now = Instant.now();
                String badge, tone;
                if ("Cancelled".equalsIgnoreCase(st)) { badge = "Cancelled"; tone = "red"; }
                else if (now.isAfter(ea.toInstant())) { badge = "Landed"; tone = "green"; }
                else if (now.isAfter(ed.toInstant())) { badge = "In flight"; tone = "green"; }
                else if (now.isAfter(board.toInstant())) { badge = "Boarding"; tone = "yellow"; }
                else if (delay > 0) { badge = "Delayed"; tone = "yellow"; }
                else { badge = "On Time"; tone = "green"; }
                long total = Duration.between(ed, ea).toMinutes(), done = Duration.between(ed.toInstant(), now).toMinutes();
                long pct = "Cancelled".equals(badge) || total <= 0 ? 0 : Math.max(0, Math.min(100, done * 100 / total));
                String via = rs.getString(13);
                String transit = via == null ? "" : rs.getString(14) + " (" + via + ")";
                return obj("flightNo", rs.getString(18), "badge", badge, "tone", tone, "progress", pct,
                        "aircraft", rs.getString(11), "reg", rs.getString(12), "duration", dur(Duration.between(sd, sa).toMinutes()),
                        "transit", transit, "transitAirport", via == null ? "" : String.valueOf(rs.getString(15)),
                        "fromCode", from, "fromCity", rs.getString(7), "fromAirport", rs.getString(8),
                        "depTime", lt(sd, from), "depDate", ld(sd, from), "depTz", lz(sd, from), "estDepTime", lt(ed, from), "boardTime", lt(board, from),
                        "depTerminal", terminal(from, fid), "depGate", gate(from, fid),
                        "toCode", to, "toCity", rs.getString(9), "toAirport", rs.getString(10),
                        "arrTime", lt(sa, to), "arrDate", ld(sa, to), "arrTz", lz(sa, to), "estArrTime", lt(ea, to),
                        "arrTerminal", terminal(to, fid), "arrGate", gate(to, fid), "belt", belt(to, fid));
            }
        }
    }

    static String book(Map<String, String> p) throws Exception {
        need(p, "passenger");
        long passengerId = Long.parseLong(p.get("_id"));
        int outId = Integer.parseInt(req(p, "flightId"));
        String outCabin = pick(p, "cabin", CABIN_FACTOR.keySet(), "Economy");
        boolean round = p.get("retFlightId") != null && !p.get("retFlightId").isBlank();
        int retId = round ? Integer.parseInt(p.get("retFlightId").trim()) : 0;
        String retCabin = round ? pick(p, "retCabin", CABIN_FACTOR.keySet(), outCabin) : null;
        if (round && retId == outId) throw new IllegalArgumentException("Choose a different return flight.");
        String method = pick(p, "method", Set.of("Card", "bKash", "Nagad", "Bank Transfer", "Cash"), "Card");
        int adults = Integer.parseInt(p.getOrDefault("adults", "1")), children = Integer.parseInt(p.getOrDefault("children", "0"));
        checkParty(adults, children);
        int pax = adults + children;
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                // lock the flight rows in id order (no deadlock) and read them
                List<Integer> ids = new ArrayList<>(List.of(outId)); if (round) ids.add(retId);
                Map<Integer, Leg> loaded = new HashMap<>();
                for (int id : new TreeSet<>(ids)) loaded.put(id, loadLeg(c, id));
                Leg out = loaded.get(outId).with(outCabin), ret = round ? loaded.get(retId).with(retCabin) : null;
                if (round) {
                    if (!ret.from().equals(out.to()) || !ret.to().equals(out.from())) throw new IllegalArgumentException("The return flight must go back to your departure airport.");
                    if (!ret.depUtc().isAfter(out.arrUtc())) throw new IllegalArgumentException("The return flight must leave after the outbound flight lands.");
                }
                List<Leg> legs = round ? List.of(out, ret) : List.of(out);
                boolean intl = legs.stream().anyMatch(l -> !l.domestic());

                // passenger details + age check against the travel date
                List<Pax> list = new ArrayList<>(); Set<String> passports = new HashSet<>();
                LocalDate firstTravel = out.localDate();
                boolean anyAdult18 = false;
                for (int i = 0; i < pax; i++) {
                    String who = "Passenger " + (i + 1) + (i < adults ? " (adult)" : " (child)");
                    String name = p.getOrDefault("p" + i + "_name", "").trim().replaceAll("\\s+", " ");
                    if (!name.matches("[\\p{L}][\\p{L} .'\\-]{1,98}")) throw new IllegalArgumentException(who + ": enter the full name as on the passport (letters only).");
                    LocalDate dob;
                    try { dob = LocalDate.parse(p.getOrDefault("p" + i + "_dob", "").trim()); }
                    catch (DateTimeException e) { throw new IllegalArgumentException(who + ": enter the date of birth."); }
                    if (dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.now().minusYears(120))) throw new IllegalArgumentException(who + ": the date of birth is not valid.");
                    for (Leg l : legs) {
                        int age = Period.between(dob, l.localDate()).getYears();
                        if (age < 2) throw new IllegalArgumentException(who + ": infants under 2 cannot be booked online (age " + age + " on " + l.localDate() + ").");
                        if (i < adults && age < 12) throw new IllegalArgumentException(who + " is " + age + " years old on " + l.localDate() + ". Passengers under 12 must be booked as children.");
                        if (i >= adults && age >= 12) throw new IllegalArgumentException(who + " is " + age + " years old on " + l.localDate() + ". Children must be 2 to 11. Please book as an adult.");
                    }
                    if (Period.between(dob, firstTravel).getYears() >= 18) anyAdult18 = true;
                    String pp = p.getOrDefault("p" + i + "_passport", "").replace(" ", "").toUpperCase();
                    if (pp.isEmpty() && intl) throw new IllegalArgumentException(who + ": passport number is required for international flights.");
                    if (!pp.isEmpty() && !pp.matches("[A-Z0-9]{6,15}")) throw new IllegalArgumentException(who + ": passport number must be 6 to 15 letters or digits.");
                    if (!pp.isEmpty() && !passports.add(pp)) throw new IllegalArgumentException(who + ": this passport number is already used for another passenger.");
                    int bag = pi(p.get("p" + i + "_bag"), 0);
                    if (!BAG_OPTIONS.contains(bag)) throw new IllegalArgumentException(who + ": choose extra baggage from the list.");
                    String meal = p.getOrDefault("p" + i + "_meal", "Standard").trim(); if (meal.isEmpty()) meal = "Standard";
                    if (!MEALS.contains(meal)) throw new IllegalArgumentException(who + ": choose a meal from the list.");
                    if ("Child meal".equals(meal) && i < adults) throw new IllegalArgumentException(who + ": the child meal is for children only.");
                    list.add(new Pax(name, dob, pp.isEmpty() ? null : pp, i < adults ? "Adult" : "Child", bag, meal, "1".equals(p.get("p" + i + "_ins"))));
                }
                if (children > 0 && !anyAdult18) throw new IllegalArgumentException("Children must travel with a passenger aged 18 or older.");

                // seats: use the chosen seat or give the next free seat of that cabin
                boolean vip = clubUntil(c, passengerId) != null;
                String group = round ? Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36).substring(0, 8).toUpperCase() : null;
                List<String> legJson = new ArrayList<>(); List<Long> bookingIds = new ArrayList<>(); long total = 0;
                for (int li = 0; li < legs.size(); li++) {
                    Leg l = legs.get(li);
                    String key = li == 0 ? "_seat" : "_rseat";
                    SeatCabin sc = layout(l.capacity()).get(l.cabin());
                    Set<String> used = usedSeats(c, l.id());
                    Set<String> mine = new HashSet<>(); String[] pick = new String[pax];
                    for (int i = 0; i < pax; i++) {
                        String s = p.getOrDefault("p" + i + key, "").trim().toUpperCase();
                        if (s.isEmpty()) continue;
                        if (!sc.seats().contains(s)) throw new IllegalArgumentException("Seat " + s + " is not in " + l.cabin() + " on flight " + l.no() + ".");
                        if (used.contains(s)) throw new IllegalArgumentException("Seat " + s + " on " + l.no() + " was just taken. Please choose another seat.");
                        if (!mine.add(s)) throw new IllegalArgumentException("Seat " + s + " is chosen twice.");
                        pick[i] = s;
                    }
                    int need = 0; for (String x : pick) if (x == null) need++;
                    List<String> avail = sc.seats().stream().filter(s -> !used.contains(s) && !mine.contains(s)).collect(java.util.stream.Collectors.toList());
                    // prefer one run of free seats in the same row; otherwise just the next free seats
                    for (int a = 0; need > 1 && a + need <= avail.size(); a++) {
                        String row = avail.get(a).substring(0, avail.get(a).length() - 1); boolean ok = true;
                        for (int b = 1; b < need && ok; b++) { String sb = avail.get(a + b); ok = sb.substring(0, sb.length() - 1).equals(row); }
                        if (ok) { avail = new ArrayList<>(avail.subList(a, avail.size())); break; }
                    }
                    Iterator<String> free = avail.iterator();
                    for (int i = 0; i < pax; i++) if (pick[i] == null) {
                        if (!free.hasNext()) {
                            long left = sc.seats().stream().filter(s -> !used.contains(s)).count();
                            throw new IllegalArgumentException(left == 0 ? l.cabin() + " is full on " + l.no() + "." : "Only " + left + " " + l.cabin() + " seat(s) left on " + l.no() + ".");
                        }
                        pick[i] = free.next();
                    }
                    long addons = addonsFor(l.domestic(), list);
                    long amount = partyTotal(l.fare(), CABIN_FACTOR.get(l.cabin()), vip, adults, children) + addons;
                    long bookingId = insert(c, "INSERT INTO BOOKING (Passenger_ID, Flight_ID, Booking_Status, Group_Ref, Leg) VALUES (?,?, 'Confirmed',?,?)", passengerId, l.id(), group, li == 0 ? "Outbound" : "Return");
                    for (int i = 0; i < pax; i++) {
                        Pax x = list.get(i);
                        insert(c, "INSERT INTO TICKET (Booking_ID, Seat_Number, Class, Passenger_Type, Pax_Name, Pax_DOB, Pax_Passport, Extra_Bag_Kg, Meal, Insurance) VALUES (?,?,?,?,?,?,?,?,?,?)",
                                bookingId, pick[i], l.cabin(), x.type(), x.name(), x.dob().toString(), x.passport(), x.bag(), x.meal(), x.ins() ? 1 : 0);
                    }
                    insert(c, "INSERT INTO PAYMENT (Booking_ID, Amount, Payment_Method, Payment_Status) VALUES (?,?,?, 'Paid')", bookingId, amount, method);
                    bookingIds.add(bookingId); total += amount;
                    legJson.add(obj("bookingId", bookingId, "leg", li == 0 ? "Outbound" : "Return", "flightNo", l.no(), "from", l.from(), "to", l.to(), "cabin", l.cabin(),
                            "date", l.localDate().toString(), "seat", String.join(", ", pick), "addons", addons, "amount", amount));
                }
                c.commit();
                return obj("bookingId", bookingIds.get(0), "bookingIds", bookingIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                        "trip", round ? "round" : "one", "amount", total, "adults", adults, "children", children, "legs", raw("[" + String.join(",", legJson) + "]"));
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    static String booking(Map<String, String> p) throws Exception {
        need(p, "staff");
        String key = req(p, "id");
        long scanned = 0; int bookingId;
        try (Connection c = conn()) {
            if (key.contains(".") || key.contains("|")) {          // boarding-pass QR text or ticket code
                scanned = ticketFromToken(key);
                try (PreparedStatement ps = c.prepareStatement("SELECT Booking_ID FROM TICKET WHERE Ticket_ID = ?")) {
                    ps.setLong(1, scanned);
                    try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new NoSuchElementException("No ticket found for that code."); bookingId = rs.getInt(1); }
                }
            } else bookingId = Integer.parseInt(key);
            String sql = "SELECT b.Booking_ID, b.Booking_Status, p.Passenger_ID, p.Name, p.Passport_No, f.Flight_ID, f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, "
                       + "(SELECT Payment_Status FROM PAYMENT WHERE Booking_ID = b.Booking_ID ORDER BY Payment_ID DESC LIMIT 1), b.Group_Ref, b.Leg "
                       + "FROM BOOKING b JOIN PASSENGER p ON p.Passenger_ID = b.Passenger_ID JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID WHERE b.Booking_ID = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NoSuchElementException("No booking found with that ID.");
                    ZonedDateTime sd = rs.getObject(10, LocalDateTime.class).atZone(ZoneOffset.UTC);
                    List<String> pax = new ArrayList<>(); String firstCabin = "", seats = "";
                    String ts = "SELECT t.Ticket_ID, COALESCE(t.Pax_Name, ?), t.Passenger_Type, t.Seat_Number, t.Class, t.Ticket_Status, "
                              + "(SELECT COUNT(*) FROM CHECK_IN ci WHERE ci.Ticket_ID = t.Ticket_ID) FROM TICKET t WHERE t.Booking_ID = ? ORDER BY t.Ticket_ID";
                    try (PreparedStatement pt = c.prepareStatement(ts)) {
                        pt.setString(1, rs.getString(4)); pt.setInt(2, bookingId);
                        try (ResultSet tr = pt.executeQuery()) {
                            while (tr.next()) {
                                if (firstCabin.isEmpty()) firstCabin = tr.getString(5);
                                seats += (seats.isEmpty() ? "" : ", ") + tr.getString(4);
                                pax.add(obj("ticketId", tr.getLong(1), "name", tr.getString(2), "type", tr.getString(3), "seat", tr.getString(4), "cabin", tr.getString(5),
                                        "status", tr.getString(6), "checkedIn", tr.getInt(7) > 0, "scanned", tr.getLong(1) == scanned));
                            }
                        }
                    }
                    return obj("bookingId", rs.getInt(1), "status", rs.getString(2), "passengerId", rs.getInt(3), "name", rs.getString(4),
                            "passport", rs.getString(5), "flightId", rs.getInt(6), "flightNo", rs.getString(7), "route", rs.getString(8) + " → " + rs.getString(9),
                            "departs", fullLocal(sd, rs.getString(8)), "payment", String.valueOf(rs.getString(11)), "vip", clubUntil(c, rs.getInt(3)) != null,
                            "cabin", firstCabin, "seat", seats, "group", rs.getString(12) == null ? "" : rs.getString(12), "leg", rs.getString(13) == null ? "" : rs.getString(13),
                            "passengers", raw("[" + String.join(",", pax) + "]"));
                }
            }
        }
    }

    static String meta(Map<String, String> p) throws Exception {
        need(p, "staff");
        StringBuilder sb = new StringBuilder("{\"staff\":[");
        try (Connection c = conn(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT Staff_ID, Name, Position FROM STAFF ORDER BY Name")) {
                boolean first = true;
                while (rs.next()) { if (!first) sb.append(','); first = false;
                    sb.append(obj("id", rs.getInt(1), "name", rs.getString(2), "position", String.valueOf(rs.getString(3)))); }
            }
            sb.append("],\"lounges\":[");
            try (ResultSet rs = st.executeQuery("SELECT Lounge_ID, Lounge_Name FROM LOUNGE ORDER BY Lounge_Name")) {
                boolean first = true;
                while (rs.next()) { if (!first) sb.append(','); first = false;
                    sb.append(obj("id", rs.getInt(1), "name", rs.getString(2))); }
            }
        }
        return sb.append("]}").toString();
    }

    static String checkin(Map<String, String> p) throws Exception {
        need(p, "staff");
        long staffId = Long.parseLong(p.get("_id"));
        boolean all = "1".equals(p.get("all"));
        List<long[]> todo = new ArrayList<>();       // {ticketId, passengerId}
        try (Connection c = conn()) {
            String base = "SELECT t.Ticket_ID, b.Passenger_ID FROM TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID WHERE t.Ticket_Status = 'Issued' AND b.Booking_Status = 'Confirmed' AND ";
            if (all) {
                try (PreparedStatement ps = c.prepareStatement(base + "b.Booking_ID = ? AND NOT EXISTS (SELECT 1 FROM CHECK_IN ci WHERE ci.Ticket_ID = t.Ticket_ID) ORDER BY t.Ticket_ID")) {
                    ps.setInt(1, Integer.parseInt(req(p, "bookingId")));
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) todo.add(new long[]{rs.getLong(1), rs.getLong(2)}); }
                }
                if (todo.isEmpty()) throw new IllegalArgumentException("Every passenger on this booking is already checked in (or the booking is cancelled).");
            } else {
                try (PreparedStatement ps = c.prepareStatement(base + "t.Ticket_ID = ?")) {
                    ps.setInt(1, Integer.parseInt(req(p, "ticketId")));
                    try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new NoSuchElementException("No valid ticket with that ID."); todo.add(new long[]{rs.getLong(1), rs.getLong(2)}); }
                }
            }
            int done = 0;
            for (long[] t : todo) {
                try { exec(c, "INSERT INTO CHECK_IN (Ticket_ID, Passenger_ID, Staff_ID) VALUES (?,?,?)", t[0], t[1], staffId); done++; }
                catch (SQLIntegrityConstraintViolationException e) { if (!all) throw new IllegalArgumentException("This ticket is already checked in."); }
            }
            return obj("ok", true, "message", done == 1 ? "1 passenger checked in." : done + " passengers checked in.");
        }
    }

    // Security, immigration and lounge records are saved for the passenger (ticket) the staff member picked, so a companion's name is recorded, not only the account holder's.
    static String paxFor(Connection c, String ticketId, long passengerId) throws SQLException {
        if (ticketId == null || ticketId.isBlank()) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(t.Pax_Name, pa.Name) FROM TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID JOIN PASSENGER pa ON pa.Passenger_ID = b.Passenger_ID WHERE t.Ticket_ID = ? AND b.Passenger_ID = ?")) {
            ps.setLong(1, Long.parseLong(ticketId.trim()));
            ps.setLong(2, passengerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("That passenger is not on this booking.");
                return rs.getString(1);
            }
        }
    }

    static Long ticketOrNull(Map<String, String> p) {
        String t = p.get("ticketId");
        return t == null || t.isBlank() ? null : Long.valueOf(t.trim());
    }

    static String security(Map<String, String> p) throws Exception {
        need(p, "staff");
        long pid = Long.parseLong(req(p, "passengerId"));
        String result = pick(p, "result", Set.of("Cleared", "Flagged", "Denied"), null);
        try (Connection c = conn()) {
            String who = paxFor(c, p.get("ticketId"), pid);
            long id = insert(c, "INSERT INTO SECURITY_CHECK (Passenger_ID, Staff_ID, Result, Remarks, Ticket_ID) VALUES (?,?,?,?,?)",
                    pid, Integer.parseInt(p.get("_id")), result, p.get("remarks"), ticketOrNull(p));
            return obj("ok", true, "message", "Security check saved" + (who == null ? "" : " for " + who) + " (record " + id + ").");
        }
    }

    static String immigration(Map<String, String> p) throws Exception {
        need(p, "staff");
        long pid = Long.parseLong(req(p, "passengerId"));
        String visa = pick(p, "visa", Set.of("Valid", "Expired", "Not Required", "Missing"), null);
        String result = pick(p, "result", Set.of("Approved", "Rejected", "Pending"), "Pending");
        try (Connection c = conn()) {
            String who = paxFor(c, p.get("ticketId"), pid);
            try (PreparedStatement fp = c.prepareStatement("SELECT o.Country_Code, d.Country_Code FROM FLIGHT f JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination WHERE f.Flight_ID = ?")) {
                fp.setInt(1, Integer.parseInt(req(p, "flightId")));
                try (ResultSet fr = fp.executeQuery()) {
                    if (!fr.next()) throw new NoSuchElementException("Flight not found.");
                    if ("BD".equals(fr.getString(1)) && "BD".equals(fr.getString(2))) throw new IllegalArgumentException("Immigration checks are only for international flights.");
                }
            }
            long id = insert(c, "INSERT INTO IMMIGRATION (Passenger_ID, Staff_ID, Flight_ID, Visa_Status, Result, Ticket_ID) VALUES (?,?,?,?,?,?)",
                    pid, Integer.parseInt(p.get("_id")), Integer.parseInt(req(p, "flightId")), visa, result, ticketOrNull(p));
            return obj("ok", true, "message", "Immigration check saved" + (who == null ? "" : " for " + who) + " (record " + id + ").");
        }
    }

    // ---- lounges, online check-in, trip progress, NovaClub receipt, sign out everywhere ----
    static final int CHECKIN_OPEN_HOURS = 48;       // online check-in opens this many hours before departure
    static final int CHECKIN_CLOSE_MINUTES = 60;    // and closes this many minutes before departure

    static String nonNull(String s) { return s == null ? "" : s; }
    static String fmtDt(LocalDateTime t) { return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }

    /** Lounge types a ticket may use: Royal Suite -> First and Premium lounges; Premium -> Premium lounge; Economy -> Premium lounge only for NovaClub VIP. */
    static Set<String> loungeTypes(String cabin, boolean vip) {
        if ("Royal Suite".equals(cabin)) return Set.of("First", "Premium");
        if ("Premium".equals(cabin) || vip) return Set.of("Premium");
        return Set.of();
    }

    static String loungeText(Connection c, String cabin, boolean vip, boolean withLocation) throws SQLException {
        Set<String> types = loungeTypes(cabin, vip);
        if (types.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT Lounge_Name, Location, Type FROM LOUNGE ORDER BY Lounge_ID")) {
            while (rs.next()) if (rs.getString(3) != null && types.contains(rs.getString(3))) names.add(withLocation ? rs.getString(1) + ", " + nonNull(rs.getString(2)) : rs.getString(1));
        }
        return String.join(withLocation ? " or " : " / ", names);
    }

    static String lounge(Map<String, String> p) throws Exception {
        need(p, "staff");
        long pid = Long.parseLong(req(p, "passengerId"));
        int loungeId = Integer.parseInt(req(p, "loungeId"));
        try (Connection c = conn()) {
            String who = paxFor(c, p.get("ticketId"), pid);
            String lname, ltype;
            try (PreparedStatement ps = c.prepareStatement("SELECT Lounge_Name, Type FROM LOUNGE WHERE Lounge_ID = ?")) {
                ps.setInt(1, loungeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NoSuchElementException("Lounge not found.");
                    lname = rs.getString(1); ltype = rs.getString(2);
                }
            }
            Long tid = ticketOrNull(p);
            if (tid != null) {
                String cabin = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT Class FROM TICKET WHERE Ticket_ID = ?")) {
                    ps.setLong(1, tid);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) cabin = rs.getString(1); }
                }
                boolean vip = clubUntil(c, pid) != null;
                if (cabin == null || ltype == null || !loungeTypes(cabin, vip).contains(ltype))
                    throw new IllegalArgumentException(nonNull(who) + " (" + cabin + ") is not eligible for " + lname + ". Royal Suite passengers use Nova Royal Lounge. Premium passengers and NovaClub VIP members use Nova Premium Lounge.");
            }
            long id = insert(c, "INSERT INTO LOUNGE_ACCESS (Passenger_ID, Lounge_ID, Ticket_ID) VALUES (?,?,?)", pid, loungeId, ticketOrNull(p));
            return obj("ok", true, "message", "Lounge entry recorded" + (who == null ? "" : " for " + who) + " (record " + id + ").");
        }
    }

    static String lounges(Map<String, String> p) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT Lounge_ID, Lounge_Name, Location, Capacity, Type FROM LOUNGE ORDER BY Lounge_ID")) {
            while (rs.next()) {
                String type = nonNull(rs.getString(5));
                String who = "First".equals(type) ? "Royal Suite passengers" : "Premium".equals(type) ? "Premium and Royal Suite passengers, and NovaClub VIP members" : "Eligible passengers";
                out.add(obj("id", rs.getInt(1), "name", rs.getString(2), "location", nonNull(rs.getString(3)), "capacity", rs.getInt(4), "type", type, "who", who));
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    /** {"1" or "0" (online check-in open?), message} */
    static String[] checkinState(LocalDateTime depUtc, String from, String status, boolean allDone) {
        if (!"Confirmed".equals(status)) return new String[]{"0", "This booking is cancelled."};
        if (allDone) return new String[]{"0", "Every passenger on this booking is checked in."};
        long mins = Duration.between(LocalDateTime.now(ZoneOffset.UTC), depUtc).toMinutes();
        if (mins < CHECKIN_CLOSE_MINUTES) return new String[]{"0", "Online check-in has closed for this flight. Please check in at the airport desk."};
        if (mins > CHECKIN_OPEN_HOURS * 60L) return new String[]{"0", "Online check-in opens " + fullLocal(depUtc.atZone(ZoneOffset.UTC).minusHours(CHECKIN_OPEN_HOURS), from) + ", " + CHECKIN_OPEN_HOURS + " hours before departure."};
        return new String[]{"1", "Online check-in is open. It closes " + CHECKIN_CLOSE_MINUTES + " minutes before departure."};
    }

    static String journey(Map<String, String> p) throws Exception {
        boolean staff = "staff".equals(p.get("_role"));
        if (!staff) need(p, "passenger");
        int bid = Integer.parseInt(req(p, "bookingId"));
        String head = "SELECT b.Booking_Status, b.Passenger_ID, b.Booking_Date, b.Group_Ref, b.Leg, f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, o.City, d.City, o.Country_Code, d.Country_Code "
                + "FROM BOOKING b JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination WHERE b.Booking_ID = ?";
        String tsql = "SELECT t.Ticket_ID, COALESCE(t.Pax_Name, pa.Name), t.Seat_Number, t.Class, t.Passenger_Type, t.Ticket_Status, "
                + "(SELECT CONCAT(DATE_FORMAT(ci.CheckIn_Time, '%Y-%m-%d %H:%i'), '|', ci.Channel) FROM CHECK_IN ci WHERE ci.Ticket_ID = t.Ticket_ID), "
                + "(SELECT CONCAT(s.Result, '|', DATE_FORMAT(s.Check_Time, '%Y-%m-%d %H:%i')) FROM SECURITY_CHECK s WHERE s.Ticket_ID = t.Ticket_ID ORDER BY s.Security_ID DESC LIMIT 1), "
                + "(SELECT CONCAT(i.Result, '|', i.Visa_Status, '|', DATE_FORMAT(i.Check_Time, '%Y-%m-%d %H:%i')) FROM IMMIGRATION i WHERE i.Ticket_ID = t.Ticket_ID ORDER BY i.Immigration_ID DESC LIMIT 1), "
                + "(SELECT GROUP_CONCAT(CONCAT(l.Lounge_Name, ' at ', DATE_FORMAT(la.Entry_Time, '%H:%i')) SEPARATOR ', ') FROM LOUNGE_ACCESS la JOIN LOUNGE l ON l.Lounge_ID = la.Lounge_ID WHERE la.Ticket_ID = t.Ticket_ID) "
                + "FROM TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID JOIN PASSENGER pa ON pa.Passenger_ID = b.Passenger_ID WHERE t.Booking_ID = ? ORDER BY t.Ticket_ID";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(head)) {
            ps.setInt(1, bid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || (!staff && rs.getLong(2) != Long.parseLong(p.get("_id")))) throw new NoSuchElementException("No booking found.");
                String status = rs.getString(1), group = nonNull(rs.getString(4)), leg = nonNull(rs.getString(5)), flightNo = rs.getString(6), from = rs.getString(7), to = rs.getString(8);
                LocalDateTime dep = rs.getObject(9, LocalDateTime.class);
                boolean domestic = "BD".equals(rs.getString(12)) && "BD".equals(rs.getString(13));
                boolean vip = clubUntil(c, rs.getLong(2)) != null;
                String booked = fmtDt(rs.getObject(3, LocalDateTime.class)), fromCity = rs.getString(10), toCity = rs.getString(11);
                List<String> tickets = new ArrayList<>();
                int total = 0, done = 0;
                try (PreparedStatement pt = c.prepareStatement(tsql)) {
                    pt.setInt(1, bid);
                    try (ResultSet tr = pt.executeQuery()) {
                        while (tr.next()) {
                            boolean cancelled = "Cancelled".equals(tr.getString(6));
                            String ci = tr.getString(7), sec = tr.getString(8), imm = tr.getString(9), visits = tr.getString(10), cabin = tr.getString(4);
                            if (!cancelled) { total++; if (ci != null) done++; }
                            String[] a = ci == null ? new String[]{"", ""} : ci.split("\\|", -1);
                            String[] s2 = sec == null ? new String[]{"", ""} : sec.split("\\|", -1);
                            String[] i2 = imm == null ? new String[]{"", "", ""} : imm.split("\\|", -1);
                            tickets.add(obj("ticketId", tr.getLong(1), "name", nonNull(tr.getString(2)), "seat", nonNull(tr.getString(3)), "cabin", cabin, "type", tr.getString(5), "status", tr.getString(6),
                                    "checkedIn", ci != null, "checkedAt", a[0], "channel", a.length > 1 ? a[1] : "",
                                    "security", s2[0], "securityAt", s2.length > 1 ? s2[1] : "",
                                    "immigration", i2[0], "visa", i2.length > 1 ? i2[1] : "", "immigrationAt", i2.length > 2 ? i2[2] : "",
                                    "loungeVisits", nonNull(visits), "lounge", loungeText(c, cabin, vip, true), "loungeNames", loungeText(c, cabin, vip, false)));
                        }
                    }
                }
                String[] st = checkinState(dep, from, status, total > 0 && done == total);
                return obj("bookingId", bid, "status", status, "reference", group.isEmpty() ? "AN-" + bid : group, "bookedOn", booked, "leg", leg,
                        "flightNo", flightNo, "route", from + " → " + to, "fromCity", fromCity, "toCity", toCity, "departs", fullLocal(dep.atZone(ZoneOffset.UTC), from),
                        "international", !domestic, "checkinOpen", "1".equals(st[0]), "checkinMessage", st[1], "vip", vip, "tickets", raw("[" + String.join(",", tickets) + "]"));
            }
        }
    }

    static String checkinOnline(Map<String, String> p) throws Exception {
        need(p, "passenger");
        long pid = Long.parseLong(p.get("_id"));
        int bid = Integer.parseInt(req(p, "bookingId"));
        try (Connection c = conn()) {
            LocalDateTime dep; String from, status;
            try (PreparedStatement ps = c.prepareStatement("SELECT b.Booking_Status, f.Departure_Time, f.Departure FROM BOOKING b JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID WHERE b.Booking_ID = ? AND b.Passenger_ID = ?")) {
                ps.setInt(1, bid); ps.setLong(2, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NoSuchElementException("No booking found.");
                    status = rs.getString(1); dep = rs.getObject(2, LocalDateTime.class); from = rs.getString(3);
                }
            }
            String[] st = checkinState(dep, from, status, false);
            if (!"1".equals(st[0])) throw new IllegalArgumentException(st[1]);
            List<Long> todo = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT t.Ticket_ID FROM TICKET t WHERE t.Booking_ID = ? AND t.Ticket_Status = 'Issued' AND NOT EXISTS (SELECT 1 FROM CHECK_IN ci WHERE ci.Ticket_ID = t.Ticket_ID) ORDER BY t.Ticket_ID")) {
                ps.setInt(1, bid);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) todo.add(rs.getLong(1)); }
            }
            if (todo.isEmpty()) throw new IllegalArgumentException("Every passenger on this booking is already checked in.");
            int n = 0;
            for (long t : todo) {
                try { exec(c, "INSERT INTO CHECK_IN (Ticket_ID, Passenger_ID, Staff_ID, Channel) VALUES (?,?,NULL,'Online')", t, pid); n++; }
                catch (SQLIntegrityConstraintViolationException e) { /* already checked in */ }
            }
            return obj("ok", true, "message", (n == 1 ? "1 passenger is" : n + " passengers are") + " checked in online. Show the e-ticket QR code at bag drop and security.");
        }
    }

    static String logoutAll(Map<String, String> p) {
        if (p.containsKey("_role")) {
            String role = p.get("_role"); long id = Long.parseLong(p.get("_id"));
            SESSIONS.entrySet().removeIf(e -> e.getValue().role().equals(role) && e.getValue().id() == id);
            try (Connection c = conn()) { exec(c, "DELETE FROM SESSION_STORE WHERE Role = ? AND User_ID = ?", role, id); } catch (Exception e) { /* ignore */ }
        }
        p.put("_set", "");
        return obj("ok", true);
    }

    static void api(HttpExchange ex, Handler h) throws IOException {
        int code = 200;
        String body;
        try {
            Map<String, String> p = params(ex);
            body = h.run(p);
            String set = p.get("_set");
            if (set != null) ex.getResponseHeaders().add("Set-Cookie", set.isEmpty() ? "aero=; Path=/; Max-Age=0" : "aero=" + set + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800" + (COOKIE_SECURE ? "; Secure" : ""));
        } catch (IllegalArgumentException | DateTimeException e) {
            code = 400; body = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (SecurityException e) {
            code = 401; body = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (SQLIntegrityConstraintViolationException e) {
            code = 409; body = "{\"error\":\"That record already exists or refers to something that does not exist.\"}";
        } catch (NoSuchElementException e) {
            code = 404; body = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            e.printStackTrace();
            code = 500; body = "{\"error\":\"Server error\"}";
        }
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void staticFile(HttpExchange ex) throws IOException {
        String rel = ex.getRequestURI().getPath();
        if (rel.equals("/")) rel = "/index.html";
        Path file = WEB.resolve(rel.substring(1)).normalize();
        if (!file.startsWith(WEB) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", ("Page not found. Looked in: " + WEB).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".css")  ? "text/css"
                    : name.endsWith(".js")   ? "text/javascript"
                    : name.endsWith(".svg")  ? "image/svg+xml"
                    : name.endsWith(".png") ? "image/png" : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : name.endsWith(".webp") ? "image/webp" : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    static final boolean COOKIE_SECURE = "1".equals(env("COOKIE_SECURE", "0"));   // set to 1 when the site is served over HTTPS
    static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "same-origin");
        if (COOKIE_SECURE) ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        if (type.startsWith("application/json")) ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = parse(ex.getRequestURI().getRawQuery());
        if ("POST".equals(ex.getRequestMethod())) {
            m.putAll(parse(new String(ex.getRequestBody().readNBytes(100_000), StandardCharsets.UTF_8)));
        }
        m.keySet().removeIf(k -> k.startsWith("_"));
        String ck = ex.getRequestHeaders().getFirst("Cookie");
        if (ck != null) for (String part : ck.split(";")) {
            part = part.trim();
            if (!part.startsWith("aero=")) continue;
            String t = part.substring(5);
            Sess s = findSession(t);
            if (s != null && s.exp() > System.currentTimeMillis()) {
                m.put("_tok", t); m.put("_role", s.role()); m.put("_id", String.valueOf(s.id())); m.put("_name", s.name());
            } else SESSIONS.remove(t);
        }
        return m;
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                  kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static void post(HttpExchange ex, Handler h) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"Use POST\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        api(ex, h);
    }

    static Connection conn() throws SQLException { return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS); }

    static long insert(String sql, Object... args) throws SQLException {
        try (Connection c = conn()) { return insert(c, sql, args); }
    }
    static long insert(Connection c, String sql, Object... args) throws SQLException {
        exec(c, sql, args);
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    static String req(Map<String, String> p, String k) {
        String v = p.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing field: " + k);
        return v.trim();
    }

    static String pick(Map<String, String> p, String k, Set<String> allowed, String def) {
        String v = p.get(k);
        if (v == null || v.isBlank()) { if (def != null) return def; throw new IllegalArgumentException("Missing field: " + k); }
        if (!allowed.contains(v)) throw new IllegalArgumentException("Invalid value for " + k + ".");
        return v;
    }

    static String obj(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            Object v = kv[i + 1];
            sb.append(q((String) kv[i])).append(':').append(v instanceof Raw r ? r.json() : v instanceof Number || v instanceof Boolean ? v.toString() : q(String.valueOf(v)));
        }
        return sb.append('}').toString();
    }

    static void need(Map<String, String> p, String role) {
        if (!role.equals(p.get("_role"))) throw new SecurityException("Please sign in.");
    }

    static void migrate() throws Exception {
        try (Connection c = conn()) {
            for (String t : new String[]{"PASSENGER", "STAFF"}) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = LOWER(?) AND COLUMN_NAME = 'Password_Hash'")) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) == 0) exec(c, "ALTER TABLE " + t + " ADD COLUMN Password_Hash VARCHAR(200) NULL");
                    }
                }
            }
            for (String col : new String[]{"Nova_Until DATE NULL", "Nova_Plan VARCHAR(20) NULL"}) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'passenger' AND COLUMN_NAME = ?")) {
                    ps.setString(1, col.split(" ")[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) == 0) exec(c, "ALTER TABLE PASSENGER ADD COLUMN " + col);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'ticket' AND COLUMN_NAME = 'Passenger_Type'")) {
                try (ResultSet rs = ps.executeQuery()) { rs.next(); if (rs.getInt(1) == 0) exec(c, "ALTER TABLE TICKET ADD COLUMN Passenger_Type ENUM('Adult','Child') NOT NULL DEFAULT 'Adult'"); }
            }
            for (String[] col : new String[][]{{"TICKET", "Pax_Name VARCHAR(100) NULL"}, {"TICKET", "Pax_DOB DATE NULL"}, {"TICKET", "Pax_Passport VARCHAR(20) NULL"},
                    {"TICKET", "Extra_Bag_Kg INT NOT NULL DEFAULT 0"}, {"TICKET", "Meal VARCHAR(20) NULL"}, {"TICKET", "Insurance TINYINT(1) NOT NULL DEFAULT 0"},
                    {"STAFF", "Role VARCHAR(10) NOT NULL DEFAULT 'Desk'"}, {"BOOKING", "Group_Ref VARCHAR(12) NULL"}, {"BOOKING", "Leg VARCHAR(10) NULL"},
                    {"SECURITY_CHECK", "Ticket_ID INT NULL"}, {"IMMIGRATION", "Ticket_ID INT NULL"}, {"LOUNGE_ACCESS", "Ticket_ID INT NULL"}, {"CHECK_IN", "Channel VARCHAR(10) NOT NULL DEFAULT 'Desk'"}}) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = LOWER(?) AND COLUMN_NAME = ?")) {
                    ps.setString(1, col[0]); ps.setString(2, col[1].split(" ")[0]);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); if (rs.getInt(1) == 0) exec(c, "ALTER TABLE " + col[0] + " ADD COLUMN " + col[1]); }
                }
            }
            exec(c, "CREATE TABLE IF NOT EXISTS SESSION_STORE (Token_Hash CHAR(64) PRIMARY KEY, Role VARCHAR(12) NOT NULL, User_ID BIGINT NOT NULL, Name VARCHAR(100) NOT NULL, Expires BIGINT NOT NULL)");
            exec(c, "CREATE TABLE IF NOT EXISTS CLUB_PAYMENT (Payment_ID INT AUTO_INCREMENT PRIMARY KEY, Passenger_ID INT NOT NULL, Plan_Name VARCHAR(10) NOT NULL, Amount DECIMAL(10,2) NOT NULL, Pay_Method VARCHAR(20) NOT NULL, Paid_At DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, Valid_From DATE NOT NULL, Valid_Until DATE NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'check_in' AND COLUMN_NAME = 'Staff_ID'")) {
                try (ResultSet rs = ps.executeQuery()) { if (rs.next() && "NO".equals(rs.getString(1))) exec(c, "ALTER TABLE CHECK_IN MODIFY Staff_ID INT NULL"); }
            }
            exec(c, "CREATE TABLE IF NOT EXISTS REVIEW (Review_ID INT AUTO_INCREMENT PRIMARY KEY, Passenger_ID INT NOT NULL, Flight_Number VARCHAR(12) NOT NULL DEFAULT '', Rating TINYINT NOT NULL, Comment VARCHAR(500) NOT NULL DEFAULT '', Created_At DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'staff' AND COLUMN_NAME = 'Staff_Code'")) {
                try (ResultSet rs = ps.executeQuery()) { rs.next(); if (rs.getInt(1) == 0) exec(c, "ALTER TABLE STAFF ADD COLUMN Staff_Code INT NULL"); }
            }
            // Staff team: sign in with the name (or email) and the staff ID as the password.
            String[][] team = {{"Wasi Sadaat", "wasi.sadaat@aeronova.test", "1095"}, {"Nabil Mahmud", "nabil.mahmud@aeronova.test", "1072"}, {"Fariba Afrin", "fariba.afrin@aeronova.test", "1119"}};
            List<Long> staffIds = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT Staff_ID FROM STAFF WHERE Staff_Code IS NULL ORDER BY Staff_ID LIMIT 3")) { while (rs.next()) staffIds.add(rs.getLong(1)); }
            for (int i = 0; i < staffIds.size(); i++)
                exec(c, "UPDATE STAFF SET Name = ?, Email = ?, Staff_Code = ?, Password_Hash = ? WHERE Staff_ID = ?", team[i][0], team[i][1], Integer.parseInt(team[i][2]), hash(team[i][2]), staffIds.get(i));
            exec(c, "UPDATE STAFF SET Role = 'Admin' WHERE LOWER(Email) = 'wasi.sadaat@aeronova.test' AND NOT EXISTS (SELECT 1 FROM (SELECT Staff_ID FROM STAFF WHERE Role = 'Admin') x)");
            exec(c, "UPDATE STAFF SET Password_Hash = ? WHERE Password_Hash IS NULL", hash(env("STAFF_PASSWORD", "staff123")));
        }
    }

    static byte[] pbk(String pw, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120000, 256)).getEncoded();
    }

    static String hash(String pw) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbk(pw, salt));
    }

    static boolean verify(String pw, String stored) throws Exception {
        if (stored == null || !stored.contains(":")) return false;
        String[] a = stored.split(":");
        return MessageDigest.isEqual(pbk(pw, Base64.getDecoder().decode(a[0])), Base64.getDecoder().decode(a[1]));
    }

    static String sha(String t) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8))); }
    static Sess findSession(String t) {
        Sess s = SESSIONS.get(t);
        if (s != null) return s;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT Role, User_ID, Name, Expires FROM SESSION_STORE WHERE Token_Hash = ? AND Expires > ?")) {
            ps.setString(1, sha(t)); ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { s = new Sess(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4)); SESSIONS.put(t, s); return s; } }
        } catch (Exception e) { /* no database: treat as signed out */ }
        return null;
    }
    static String session(Map<String, String> p, String role, long id, String name) {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long exp = System.currentTimeMillis() + 8 * 3600_000L;
        SESSIONS.put(t, new Sess(role, id, name, exp));
        try (Connection c = conn()) {
            exec(c, "DELETE FROM SESSION_STORE WHERE Expires < ?", System.currentTimeMillis());
            exec(c, "INSERT INTO SESSION_STORE (Token_Hash, Role, User_ID, Name, Expires) VALUES (?,?,?,?,?)", sha(t), role, id, name, exp);
        } catch (Exception e) { /* still works until restart */ }
        p.put("_set", t);
        return obj("loggedIn", true, "role", role, "name", name);
    }

    static String me(Map<String, String> p) {
        if (!p.containsKey("_role")) return obj("loggedIn", false);
        boolean vip = false; String until = "";
        if ("passenger".equals(p.get("_role"))) {
            try (Connection c = conn()) {
                LocalDate u = clubUntil(c, Long.parseLong(p.get("_id")));
                if (u != null) { vip = true; until = u.toString(); }
            } catch (Exception e) { /* treat as non-member */ }
        }
        return obj("loggedIn", true, "role", p.get("_role"), "name", p.get("_name"), "vip", vip, "until", until, "admin", "staff".equals(p.get("_role")) && isAdmin(p.get("_id")));
    }

    static String logout(Map<String, String> p) {
        if (p.get("_tok") != null) {
            SESSIONS.remove(p.get("_tok"));
            try (Connection c = conn()) { exec(c, "DELETE FROM SESSION_STORE WHERE Token_Hash = ?", sha(p.get("_tok"))); } catch (Exception e) { /* ignore */ }
        }
        p.put("_set", "");
        return obj("ok", true);
    }

    static String register(Map<String, String> p) throws Exception {
        String name = req(p, "name"), email = req(p, "email").toLowerCase(), passport = req(p, "passport"), pw = req(p, "password");
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Enter a valid email address.");
        if (pw.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");
        String gender = p.getOrDefault("gender", "");
        if (!Set.of("Male", "Female", "Other").contains(gender)) gender = null;
        try (Connection c = conn()) {
            long claim = 0; int rows = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT Passenger_ID, Passport_No, Email, Password_Hash FROM PASSENGER WHERE Passport_No = ? OR LOWER(Email) = ?")) {
                ps.setString(1, passport); ps.setString(2, email);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows++;
                        if (passport.equals(rs.getString(2)) && email.equalsIgnoreCase(rs.getString(3)) && rs.getString(4) == null) claim = rs.getLong(1);
                    }
                }
            }
            if (rows > 1 || (rows == 1 && claim == 0)) throw new IllegalArgumentException("That email or passport number is already registered. Please sign in.");
            long id;
            if (claim == 0) id = insert(c, "INSERT INTO PASSENGER (Name, Passport_No, Gender, Nationality, Phone, Email, Password_Hash) VALUES (?,?,?,?,?,?,?)",
                    name, passport, gender, p.getOrDefault("nationality", "Bangladeshi"), p.get("phone"), email, hash(pw));
            else { exec(c, "UPDATE PASSENGER SET Name = ?, Phone = ?, Password_Hash = ? WHERE Passenger_ID = ?", name, p.get("phone"), hash(pw), claim); id = claim; }
            return session(p, "passenger", id, name);
        }
    }

    static String login(Map<String, String> p) throws Exception {
        boolean staff = "staff".equals(p.get("type"));
        String email = req(p, "email").toLowerCase(), pw = req(p, "password"), key = (staff ? "s:" : "p:") + email;
        long now = System.currentTimeMillis();
        long[] f = FAILS.get(key);
        if (f != null && now - f[1] > 600_000) { FAILS.remove(key); f = null; }
        if (f != null && f[0] >= 5) throw new IllegalArgumentException("Too many wrong attempts. Try again in 10 minutes.");
        String sql = staff ? "SELECT Staff_ID, Name, Password_Hash FROM STAFF WHERE LOWER(Email) = ? OR LOWER(Name) = ?"
                           : "SELECT Passenger_ID, Name, Password_Hash FROM PASSENGER WHERE LOWER(Email) = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            if (staff) ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && verify(pw, rs.getString(3))) {
                    FAILS.remove(key);
                    return session(p, staff ? "staff" : "passenger", rs.getLong(1), rs.getString(2));
                }
            }
        }
        FAILS.computeIfAbsent(key, k -> new long[]{0, now})[0]++;
        throw new SecurityException("Wrong email or password.");
    }

    static String mybookings(Map<String, String> p) throws Exception {
        need(p, "passenger");
        String sql = "SELECT b.Booking_ID, b.Booking_Status, GROUP_CONCAT(t.Seat_Number ORDER BY t.Ticket_ID SEPARATOR ', '), MIN(t.Class), f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, "
                   + "(SELECT COALESCE(SUM(CASE Payment_Status WHEN 'Paid' THEN Amount WHEN 'Refunded' THEN -Amount ELSE 0 END), 0) FROM PAYMENT WHERE Booking_ID = b.Booking_ID), "
                   + "(SELECT COUNT(*) FROM CHECK_IN ci JOIN TICKET t2 ON t2.Ticket_ID = ci.Ticket_ID WHERE t2.Booking_ID = b.Booking_ID), SUM(t.Passenger_Type = 'Adult'), SUM(t.Passenger_Type = 'Child'), "
                   + "b.Group_Ref, b.Leg, GROUP_CONCAT(COALESCE(t.Pax_Name, p.Name) ORDER BY t.Ticket_ID SEPARATOR ', '), b.Booking_Date "
                   + "FROM BOOKING b JOIN TICKET t ON t.Booking_ID = b.Booking_ID JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID JOIN PASSENGER p ON p.Passenger_ID = b.Passenger_ID "
                   + "WHERE b.Passenger_ID = ? GROUP BY b.Booking_ID, b.Booking_Status, f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, b.Group_Ref, b.Leg, b.Booking_Date ORDER BY f.Departure_Time DESC";
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(p.get("_id")));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime dep = rs.getObject(8, LocalDateTime.class);
                    boolean checked = rs.getInt(10) > 0;
                    boolean can = "Confirmed".equals(rs.getString(2)) && !checked && dep.isAfter(LocalDateTime.now(ZoneOffset.UTC));
                    out.add(obj("bookingId", rs.getInt(1), "status", rs.getString(2), "seat", rs.getString(3), "cabin", rs.getString(4),
                            "flightNo", rs.getString(5), "route", rs.getString(6) + " → " + rs.getString(7), "departs", fullLocal(dep.atZone(ZoneOffset.UTC), rs.getString(6)),
                            "netPaid", Math.round(rs.getDouble(9)), "adults", rs.getInt(11), "children", rs.getInt(12), "checkedIn", checked, "canCancel", can,
                            "group", rs.getString(13) == null ? "" : rs.getString(13), "leg", rs.getString(14) == null ? "" : rs.getString(14), "names", rs.getString(15),
                            "bookedOn", fmtDt(rs.getObject(16, LocalDateTime.class)), "allCheckedIn", rs.getInt(10) >= rs.getInt(11) + rs.getInt(12),
                            "canCheckIn", "1".equals(checkinState(dep, rs.getString(6), rs.getString(2), rs.getInt(10) >= rs.getInt(11) + rs.getInt(12))[0])));
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    static String cancel(Map<String, String> p) throws Exception {
        need(p, "passenger");
        int bookingId = Integer.parseInt(req(p, "bookingId"));
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT b.Booking_Status, f.Departure_Time, (SELECT COUNT(*) FROM CHECK_IN ci JOIN TICKET t ON t.Ticket_ID = ci.Ticket_ID WHERE t.Booking_ID = b.Booking_ID) "
                        + "FROM BOOKING b JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID WHERE b.Booking_ID = ? AND b.Passenger_ID = ? FOR UPDATE")) {
                    ps.setInt(1, bookingId);
                    ps.setLong(2, Long.parseLong(p.get("_id")));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new NoSuchElementException("No booking found.");
                        if (!"Confirmed".equals(rs.getString(1))) throw new IllegalArgumentException("This booking is already cancelled.");
                        if (rs.getObject(2, LocalDateTime.class).isBefore(LocalDateTime.now(ZoneOffset.UTC))) throw new IllegalArgumentException("This flight has already departed.");
                        if (rs.getInt(3) > 0) throw new IllegalArgumentException("You are already checked in. Please contact the airport desk.");
                    }
                }
                exec(c, "UPDATE BOOKING SET Booking_Status = 'Cancelled' WHERE Booking_ID = ?", bookingId);
                exec(c, "UPDATE TICKET SET Ticket_Status = 'Cancelled' WHERE Booking_ID = ?", bookingId);
                exec(c, "INSERT INTO PAYMENT (Booking_ID, Amount, Payment_Method, Payment_Status) SELECT Booking_ID, SUM(CASE Payment_Status WHEN 'Paid' THEN Amount WHEN 'Refunded' THEN -Amount ELSE 0 END), MAX(Payment_Method), 'Refunded' FROM PAYMENT WHERE Booking_ID = ? GROUP BY Booking_ID HAVING SUM(CASE Payment_Status WHEN 'Paid' THEN Amount WHEN 'Refunded' THEN -Amount ELSE 0 END) > 0", bookingId);
                c.commit();
                return obj("ok", true, "message", "Booking cancelled and refunded.");
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    static String report(Map<String, String> p) throws Exception {
        need(p, "staff");
        String day = LocalDate.parse(p.getOrDefault("date", LocalDate.now().toString())).toString();
        try (Connection c = conn()) {
            long total = 0, confirmed = 0, cancelled = 0, revenue = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(Booking_Status = 'Confirmed'), 0), COALESCE(SUM(Booking_Status = 'Cancelled'), 0) FROM BOOKING WHERE DATE(Booking_Date) = ?")) {
                ps.setString(1, day);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); confirmed = rs.getLong(2); cancelled = rs.getLong(3); }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(CASE Payment_Status WHEN 'Paid' THEN Amount WHEN 'Refunded' THEN -Amount ELSE 0 END), 0) FROM PAYMENT WHERE DATE(Payment_Date) = ?")) {
                ps.setString(1, day);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); revenue = Math.round(rs.getDouble(1)); }
            }
            List<String> flights = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, f.Status, a.Capacity, "
                    + "(SELECT COUNT(*) FROM BOOKING b JOIN TICKET t ON t.Booking_ID = b.Booking_ID WHERE b.Flight_ID = f.Flight_ID AND t.Ticket_Status <> 'Cancelled'), "
                    + "(SELECT COUNT(*) FROM CHECK_IN ci JOIN TICKET t ON t.Ticket_ID = ci.Ticket_ID JOIN BOOKING b ON b.Booking_ID = t.Booking_ID WHERE b.Flight_ID = f.Flight_ID) "
                    + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID WHERE f.Departure_Time >= ? AND f.Departure_Time < ? ORDER BY f.Departure_Time")) {
                LocalDate dd = LocalDate.parse(day);
                ps.setObject(1, dd.atStartOfDay(zone("DAC")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
                ps.setObject(2, dd.plusDays(1).atStartOfDay(zone("DAC")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) flights.add(obj("flightNo", rs.getString(1), "route", rs.getString(2) + " → " + rs.getString(3),
                            "departs", lt(rs.getObject(4, LocalDateTime.class).atZone(ZoneOffset.UTC), rs.getString(2)) + " (" + lz(rs.getObject(4, LocalDateTime.class).atZone(ZoneOffset.UTC), rs.getString(2)) + ")", "status", rs.getString(5),
                            "capacity", rs.getInt(6), "booked", rs.getInt(7), "checkedIn", rs.getInt(8)));
                }
            }
            return "{\"date\":" + q(day) + ",\"bookings\":" + total + ",\"confirmed\":" + confirmed + ",\"cancelled\":" + cancelled
                    + ",\"revenue\":" + revenue + ",\"flights\":[" + String.join(",", flights) + "]}";
        }
    }

    // ---- v5: cabins, seat layout, legs (round trip), passenger details ----
    static final String[] CABINS = {"Economy", "Premium", "Royal Suite"};
    record Raw(String json) {}
    static Raw raw(String json) { return new Raw(json); }
    record Pax(String name, LocalDate dob, String passport, String type, int bag, String meal, boolean ins) {}
    record SeatCabin(String name, String[] groups, List<String> seats, int firstRow, int lastRow) {}
    record Leg(int id, String no, String from, String to, LocalDateTime depUtc, LocalDateTime arrUtc, double fare, int capacity, boolean domestic, String cabin, String status) {
        Leg with(String c) { return new Leg(id, no, from, to, depUtc, arrUtc, fare, capacity, domestic, c, status); }
        LocalDate localDate() { return depUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone(from)).toLocalDate(); }
    }

    /** Seat plan from the aircraft capacity: Royal Suite at the front, then Premium, then Economy. The total always equals the capacity. */
    static Map<String, SeatCabin> layout(int cap) {
        boolean wide = cap >= 250, small = cap <= 100;
        String[] rg = {"A", "D"};
        String[] pg = wide ? new String[]{"AB", "CD", "EF"} : new String[]{"AB", "CD"};
        String[] eg = wide ? new String[]{"ABC", "DEF", "GHJ"} : small ? new String[]{"AB", "CD"} : new String[]{"ABC", "DEF"};
        int royal = (wide ? 4 : small ? 1 : 2) * 2;
        int premium = (wide ? 5 : small ? 2 : 3) * String.join("", pg).length();
        Map<String, SeatCabin> m = new LinkedHashMap<>();
        int next = addCabin(m, "Royal Suite", rg, royal, 1);
        next = addCabin(m, "Premium", pg, premium, next);
        addCabin(m, "Economy", eg, cap - royal - premium, next);
        return m;
    }
    static int addCabin(Map<String, SeatCabin> m, String name, String[] groups, int count, int startRow) {
        String letters = String.join("", groups);
        List<String> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) seats.add((startRow + i / letters.length()) + "" + letters.charAt(i % letters.length()));
        int last = startRow + Math.max(0, (count - 1) / letters.length());
        m.put(name, new SeatCabin(name, groups, seats, startRow, last));
        return last + 1;
    }

    static Map<String, Integer> soldByCabin(Connection c, int flightId) throws SQLException {
        Map<String, Integer> m = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.Class, COUNT(*) FROM TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID WHERE b.Flight_ID = ? AND t.Ticket_Status <> 'Cancelled' GROUP BY t.Class")) {
            ps.setInt(1, flightId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) m.put(rs.getString(1), rs.getInt(2)); }
        }
        return m;
    }

    static Set<String> usedSeats(Connection c, int flightId) throws SQLException {
        Set<String> used = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.Seat_Number FROM TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID WHERE b.Flight_ID = ? AND t.Ticket_Status <> 'Cancelled'")) {
            ps.setInt(1, flightId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) used.add(rs.getString(1)); }
        }
        return used;
    }

    static Leg loadLeg(Connection c, int flightId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, f.Arrival_Time, f.Base_Fare, f.Status, a.Capacity, o.Country_Code, d.Country_Code "
                + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination WHERE f.Flight_ID = ? FOR UPDATE")) {
            ps.setInt(1, flightId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Flight not found.");
                if ("Cancelled".equals(rs.getString(7))) throw new IllegalArgumentException("Flight " + rs.getString(1) + " is cancelled.");
                LocalDateTime dep = rs.getObject(4, LocalDateTime.class);
                if (dep.isBefore(LocalDateTime.now(ZoneOffset.UTC))) throw new IllegalArgumentException("Flight " + rs.getString(1) + " has already departed.");
                return new Leg(flightId, rs.getString(1), rs.getString(2), rs.getString(3), dep, rs.getObject(5, LocalDateTime.class), rs.getDouble(6), rs.getInt(8),
                        "BD".equals(rs.getString(9)) && "BD".equals(rs.getString(10)), "Economy", rs.getString(7));
            }
        }
    }

    static String fullLocal(ZonedDateTime utc, String airport) { return utc.withZoneSameInstant(zone(airport)).format(FULL) + " (" + lz(utc, airport) + ")"; }

    // seat map for one flight and cabin: GET /api/seats?flightId=..&cabin=..
    static String seatMap(Map<String, String> p) throws Exception {
        int fid = Integer.parseInt(req(p, "flightId"));
        String cabin = pick(p, "cabin", CABIN_FACTOR.keySet(), "Economy");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT a.Capacity FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID WHERE f.Flight_ID = ?")) {
            ps.setInt(1, fid);
            int cap;
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new NoSuchElementException("Flight not found."); cap = rs.getInt(1); }
            SeatCabin sc = layout(cap).get(cabin);
            Set<String> used = usedSeats(c, fid);
            List<String> rows = new ArrayList<>(), taken = new ArrayList<>();
            for (int r = sc.firstRow(); r <= sc.lastRow(); r++) {
                StringBuilder l = new StringBuilder();
                for (String s : sc.seats()) if (s.substring(0, s.length() - 1).equals(String.valueOf(r))) l.append(s.charAt(s.length() - 1));
                rows.add(obj("r", r, "l", l.toString()));
            }
            for (String s : sc.seats()) if (used.contains(s)) taken.add(q(s));
            List<String> gs = new ArrayList<>(); for (String g : sc.groups()) gs.add(q(g));
            return obj("cabin", cabin, "groups", raw("[" + String.join(",", gs) + "]"), "rows", raw("[" + String.join(",", rows) + "]"),
                    "taken", raw("[" + String.join(",", taken) + "]"), "total", sc.seats().size(), "free", sc.seats().size() - taken.size());
        }
    }

    // ---- e-ticket / boarding pass ----
    static final String QR_SECRET = env("QR_SECRET", "aeronova-change-this-secret");
    static String token(long ticketId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(QR_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(String.valueOf(ticketId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (int i = 0; i < 5; i++) sb.append(String.format("%02x", h[i]));
            return ticketId + "." + sb;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static long ticketFromToken(String s) {
        s = s.trim(); int bar = s.lastIndexOf('|'); if (bar >= 0) s = s.substring(bar + 1);
        String[] a = s.split("\\.");
        try {
            long id = Long.parseLong(a[0]);
            if (a.length == 2 && MessageDigest.isEqual(token(id).getBytes(StandardCharsets.UTF_8), s.getBytes(StandardCharsets.UTF_8))) return id;
        } catch (NumberFormatException e) { /* fall through */ }
        throw new IllegalArgumentException("This ticket code is not valid.");
    }

    static String eticket(Map<String, String> p) throws Exception {
        boolean staff = "staff".equals(p.get("_role"));
        if (!staff) need(p, "passenger");
        List<String> out = new ArrayList<>();
        String[] ids = req(p, "ids").split(",");
        if (ids.length > 4) throw new IllegalArgumentException("Too many bookings.");
        try (Connection c = conn()) {
            for (String sid : ids) {
                int bid = Integer.parseInt(sid.trim());
                String sql = "SELECT b.Booking_ID, b.Booking_Status, b.Passenger_ID, b.Group_Ref, b.Leg, f.Flight_ID, f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, f.Arrival_Time, "
                           + "o.City, o.Airport_Name, o.Country_Code, d.City, d.Airport_Name, d.Country_Code, a.Model, p.Name, "
                           + "(SELECT COALESCE(SUM(CASE Payment_Status WHEN 'Paid' THEN Amount WHEN 'Refunded' THEN -Amount ELSE 0 END), 0) FROM PAYMENT WHERE Booking_ID = b.Booking_ID) "
                           + "FROM BOOKING b JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID JOIN AIRPORT o ON o.Airport_Code = f.Departure "
                           + "JOIN AIRPORT d ON d.Airport_Code = f.Destination JOIN PASSENGER p ON p.Passenger_ID = b.Passenger_ID WHERE b.Booking_ID = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, bid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || (!staff && rs.getLong(3) != Long.parseLong(p.get("_id")))) throw new NoSuchElementException("No booking found.");
                        int fid = rs.getInt(6); String from = rs.getString(8), to = rs.getString(9);
                        ZonedDateTime sd = rs.getObject(10, LocalDateTime.class).atZone(ZoneOffset.UTC), sa = rs.getObject(11, LocalDateTime.class).atZone(ZoneOffset.UTC);
                        boolean domestic = "BD".equals(rs.getString(14)) && "BD".equals(rs.getString(17));
                        ZonedDateTime board = sd.minusMinutes(domestic ? 40 : 60);
                        List<String> tickets = new ArrayList<>();
                        try (PreparedStatement pt = c.prepareStatement("SELECT t.Ticket_ID, COALESCE(t.Pax_Name, ?), t.Pax_Passport, t.Seat_Number, t.Class, t.Passenger_Type, t.Ticket_Status, "
                                + "(SELECT COUNT(*) FROM CHECK_IN ci WHERE ci.Ticket_ID = t.Ticket_ID), t.Meal, t.Extra_Bag_Kg, t.Insurance FROM TICKET t WHERE t.Booking_ID = ? ORDER BY t.Ticket_ID")) {
                            pt.setString(1, rs.getString(19)); pt.setInt(2, bid);
                            try (ResultSet tr = pt.executeQuery()) {
                                while (tr.next()) {
                                    String pp = tr.getString(3);
                                    String masked = pp == null ? "" : (pp.length() <= 3 ? pp : "*".repeat(pp.length() - 3) + pp.substring(pp.length() - 3));
                                    tickets.add(obj("ticketId", tr.getLong(1), "name", tr.getString(2), "passport", masked, "seat", tr.getString(4), "cabin", tr.getString(5),
                                            "type", tr.getString(6), "status", tr.getString(7), "checkedIn", tr.getInt(8) > 0, "token", token(tr.getLong(1)),
                                            "meal", tr.getString(9) == null ? "Standard" : tr.getString(9), "extraBag", tr.getInt(10), "insurance", tr.getInt(11) > 0));
                                }
                            }
                        }
                        out.add(obj("bookingId", bid, "status", rs.getString(2), "group", rs.getString(4) == null ? "" : rs.getString(4), "leg", rs.getString(5) == null ? "" : rs.getString(5),
                                "flightNo", rs.getString(7), "aircraft", rs.getString(18), "paid", Math.round(rs.getDouble(20)), "bookedBy", rs.getString(19),
                                "fromCode", from, "fromCity", rs.getString(12), "fromAirport", rs.getString(13), "toCode", to, "toCity", rs.getString(15), "toAirport", rs.getString(16),
                                "depDate", ld(sd, from), "depTime", lt(sd, from), "depTz", lz(sd, from), "arrDate", ld(sa, to), "arrTime", lt(sa, to), "arrTz", lz(sa, to),
                                "boardTime", lt(board, from), "terminal", terminal(from, fid), "gate", gate(from, fid), "international", !domestic,
                                "tickets", raw("[" + String.join(",", tickets) + "]")));
                    }
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    static String profile(Map<String, String> p) throws Exception {
        need(p, "passenger");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT Name, Passport_No, Phone, Email, COALESCE(Gender,''), COALESCE(Nationality,'') FROM PASSENGER WHERE Passenger_ID = ?")) {
            ps.setLong(1, Long.parseLong(p.get("_id")));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Account not found.");
                return obj("name", rs.getString(1), "passport", rs.getString(2), "phone", String.valueOf(rs.getString(3)), "email", rs.getString(4), "gender", rs.getString(5), "nationality", rs.getString(6));
            }
        }
    }

    // ================= v6: add-ons, reschedule, account, staff management =================
    static final long BAG_RATE_DOM = 120, BAG_RATE_INTL = 450;      // BDT per extra kg, per passenger, per flight
    static final long INS_DOM = 300, INS_INTL = 900;                // travel insurance, per passenger, per flight
    static final long CHANGE_FEE_DOM = 500, CHANGE_FEE_INTL = 1500; // flight change fee, per passenger
    static final List<String> MEALS = List.of("Standard", "Vegetarian", "Vegan", "Halal", "Diabetic", "Child meal");
    static final List<Integer> BAG_OPTIONS = List.of(0, 5, 10, 15, 20, 30);

    static long addonsFor(boolean domestic, List<Pax> list) {
        long t = 0;
        for (Pax x : list) t += x.bag() * (domestic ? BAG_RATE_DOM : BAG_RATE_INTL) + (x.ins() ? (domestic ? INS_DOM : INS_INTL) : 0);
        return t;
    }
    static int pi(String s, int def) { try { return s == null || s.isBlank() ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number."); } }

    static String addons(Map<String, String> p) {
        List<String> m = new ArrayList<>(); for (String s : MEALS) m.add(q(s));
        return obj("bagDom", BAG_RATE_DOM, "bagIntl", BAG_RATE_INTL, "insDom", INS_DOM, "insIntl", INS_INTL, "feeDom", CHANGE_FEE_DOM, "feeIntl", CHANGE_FEE_INTL,
                "meals", raw("[" + String.join(",", m) + "]"), "bags", raw(BAG_OPTIONS.toString()));
    }

    // ---- account ----
    static String profileUpdate(Map<String, String> p) throws Exception {
        need(p, "passenger");
        String name = req(p, "name").replaceAll("\\s+", " ");
        if (!name.matches("[\\p{L}][\\p{L} .'\\-]{1,98}")) throw new IllegalArgumentException("Enter your full name (letters only).");
        String phone = p.getOrDefault("phone", "").trim(); if (phone.length() > 25) throw new IllegalArgumentException("Phone number is too long.");
        String gender = p.getOrDefault("gender", ""); if (!Set.of("Male", "Female", "Other").contains(gender)) gender = null;
        String nat = p.getOrDefault("nationality", "").trim(); if (nat.length() > 60) nat = nat.substring(0, 60);
        try (Connection c = conn()) { exec(c, "UPDATE PASSENGER SET Name = ?, Phone = ?, Gender = ?, Nationality = ? WHERE Passenger_ID = ?", name, phone, gender, nat, Long.parseLong(p.get("_id"))); }
        return obj("ok", true, "message", "Profile saved.");
    }

    static String changePassword(Map<String, String> p) throws Exception {
        String role = p.get("_role"); if (role == null) throw new SecurityException("Please sign in.");
        boolean staff = "staff".equals(role);
        String oldPw = req(p, "oldPassword"), pw = req(p, "newPassword");
        if (pw.length() < (staff ? 8 : 6)) throw new IllegalArgumentException("The new password must be at least " + (staff ? 8 : 6) + " characters.");
        String table = staff ? "STAFF" : "PASSENGER", idCol = staff ? "Staff_ID" : "Passenger_ID";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT Password_Hash FROM " + table + " WHERE " + idCol + " = ?")) {
            ps.setLong(1, Long.parseLong(p.get("_id")));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !verify(oldPw, rs.getString(1))) throw new IllegalArgumentException("The current password is not correct.");
            }
            exec(c, "UPDATE " + table + " SET Password_Hash = ? WHERE " + idCol + " = ?", hash(pw), Long.parseLong(p.get("_id")));
        }
        return obj("ok", true, "message", "Password changed.");
    }

    /** Forgot password without e-mail: the account's e-mail AND passport number must both match. */
    static String resetPassword(Map<String, String> p) throws Exception {
        String email = req(p, "email").toLowerCase(), passport = req(p, "passport").replace(" ", "").toUpperCase(), pw = req(p, "password");
        if (pw.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");
        String key = "r:" + email; long now = System.currentTimeMillis(); long[] f = FAILS.get(key);
        if (f != null && now - f[1] > 600_000) { FAILS.remove(key); f = null; }
        if (f != null && f[0] >= 5) throw new IllegalArgumentException("Too many attempts. Try again in 10 minutes.");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT Passenger_ID FROM PASSENGER WHERE LOWER(Email) = ? AND UPPER(REPLACE(Passport_No,' ','')) = ? AND Password_Hash IS NOT NULL")) {
            ps.setString(1, email); ps.setString(2, passport);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { exec(c, "UPDATE PASSENGER SET Password_Hash = ? WHERE Passenger_ID = ?", hash(pw), rs.getLong(1)); FAILS.remove(key); return obj("ok", true, "message", "Password changed. You can sign in now."); }
            }
        }
        FAILS.computeIfAbsent(key, k -> new long[]{0, now})[0]++;
        throw new IllegalArgumentException("Email and passport number do not match any account.");
    }

    // ---- change flight (reschedule) ----
    record RB(int bookingId, long ownerId, int flightId, String cabin, int adults, int children, String from, String to, boolean domestic, String group, String leg, int checked) {}
    static RB loadBooking(Connection c, int bookingId, long owner) throws SQLException {
        String sql = "SELECT b.Booking_ID, b.Passenger_ID, b.Flight_ID, b.Booking_Status, MIN(t.Class), SUM(t.Passenger_Type='Adult'), SUM(t.Passenger_Type='Child'), f.Departure, f.Destination, o.Country_Code, d.Country_Code, b.Group_Ref, b.Leg, "
                   + "(SELECT COUNT(*) FROM CHECK_IN ci JOIN TICKET t2 ON t2.Ticket_ID = ci.Ticket_ID WHERE t2.Booking_ID = b.Booking_ID), f.Departure_Time "
                   + "FROM BOOKING b JOIN TICKET t ON t.Booking_ID = b.Booking_ID AND t.Ticket_Status <> 'Cancelled' JOIN FLIGHT f ON f.Flight_ID = b.Flight_ID JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination "
                   + "WHERE b.Booking_ID = ? AND b.Passenger_ID = ? GROUP BY b.Booking_ID, b.Passenger_ID, b.Flight_ID, b.Booking_Status, f.Departure, f.Destination, o.Country_Code, d.Country_Code, b.Group_Ref, b.Leg, f.Departure_Time";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, bookingId); ps.setLong(2, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("No booking found.");
                if (!"Confirmed".equals(rs.getString(4))) throw new IllegalArgumentException("This booking is cancelled.");
                if (rs.getInt(14) > 0) throw new IllegalArgumentException("You are already checked in. Please contact the airport desk.");
                if (rs.getObject(15, LocalDateTime.class).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusHours(4))) throw new IllegalArgumentException("Flights can be changed until 4 hours before departure.");
                return new RB(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9), "BD".equals(rs.getString(10)) && "BD".equals(rs.getString(11)), rs.getString(12), rs.getString(13), rs.getInt(14));
            }
        }
    }

    static String rescheduleOptions(Map<String, String> p) throws Exception {
        need(p, "passenger");
        LocalDate date = LocalDate.parse(req(p, "date"));
        try (Connection c = conn()) {
            RB b = loadBooking(c, Integer.parseInt(req(p, "bookingId")), Long.parseLong(p.get("_id")));
            boolean vip = clubUntil(c, b.ownerId()) != null; double factor = CABIN_FACTOR.get(b.cabin());
            double oldBase; try (PreparedStatement ps = c.prepareStatement("SELECT Base_Fare FROM FLIGHT WHERE Flight_ID = ?")) { ps.setInt(1, b.flightId()); try (ResultSet rs = ps.executeQuery()) { rs.next(); oldBase = rs.getDouble(1); } }
            long oldTotal = partyTotal(oldBase, factor, vip, b.adults(), b.children()), fee = (b.domestic() ? CHANGE_FEE_DOM : CHANGE_FEE_INTL) * (b.adults() + b.children());
            ZoneId oz = zone(b.from());
            LocalDateTime s = date.atStartOfDay(oz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), e = date.plusDays(1).atStartOfDay(oz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT f.Flight_ID, f.Flight_Number, f.Departure_Time, f.Arrival_Time, f.Base_Fare, a.Capacity FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID "
                    + "WHERE f.Departure = ? AND f.Destination = ? AND f.Departure_Time >= ? AND f.Departure_Time < ? AND f.Departure_Time > UTC_TIMESTAMP() AND f.Status <> 'Cancelled' AND f.Flight_ID <> ? ORDER BY f.Departure_Time")) {
                ps.setString(1, b.from()); ps.setString(2, b.to()); ps.setObject(3, s); ps.setObject(4, e); ps.setInt(5, b.flightId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ZonedDateTime sd = rs.getObject(3, LocalDateTime.class).atZone(ZoneOffset.UTC), sa = rs.getObject(4, LocalDateTime.class).atZone(ZoneOffset.UTC);
                        int left = layout(rs.getInt(6)).get(b.cabin()).seats().size() - soldByCabin(c, rs.getInt(1)).getOrDefault(b.cabin(), 0);
                        long nt = partyTotal(rs.getDouble(5), factor, vip, b.adults(), b.children());
                        out.add(obj("id", rs.getInt(1), "no", rs.getString(2), "dep", lt(sd, b.from()), "depTz", lz(sd, b.from()), "arr", lt(sa, b.to()), "arrDate", ld(sa, b.to()), "seatsLeft", Math.max(0, left),
                                "ok", left >= b.adults() + b.children(), "fareDiff", nt - oldTotal, "fee", fee, "pay", nt - oldTotal + fee));
                    }
                }
            }
            return obj("cabin", b.cabin(), "route", b.from() + " → " + b.to(), "fee", fee, "flights", raw("[" + String.join(",", out) + "]"));
        }
    }

    static String reschedule(Map<String, String> p) throws Exception {
        need(p, "passenger");
        int bookingId = Integer.parseInt(req(p, "bookingId")), newId = Integer.parseInt(req(p, "newFlightId"));
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                RB b = loadBooking(c, bookingId, Long.parseLong(p.get("_id")));
                if (newId == b.flightId()) throw new IllegalArgumentException("Choose a different flight.");
                List<Integer> ids = new ArrayList<>(List.of(b.flightId(), newId)); Collections.sort(ids);
                Map<Integer, Leg> L = new HashMap<>(); for (int id : ids) L.put(id, loadLegAny(c, id));
                Leg old = L.get(b.flightId()), nw = L.get(newId);
                if (!nw.from().equals(b.from()) || !nw.to().equals(b.to())) throw new IllegalArgumentException("You can only change to another flight on the same route.");
                if (nw.depUtc().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusHours(4))) throw new IllegalArgumentException("That flight leaves too soon. Choose a later flight.");
                if ("Cancelled".equals(nw.status())) throw new IllegalArgumentException("That flight is cancelled.");
                if (b.group() != null) {      // keep the round trip in order
                    try (PreparedStatement ps = c.prepareStatement("SELECT f.Departure_Time, f.Arrival_Time FROM BOOKING x JOIN FLIGHT f ON f.Flight_ID = x.Flight_ID WHERE x.Group_Ref = ? AND x.Booking_ID <> ? AND x.Booking_Status = 'Confirmed'")) {
                        ps.setString(1, b.group()); ps.setInt(2, bookingId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                if ("Outbound".equals(b.leg()) && !nw.arrUtc().isBefore(rs.getObject(1, LocalDateTime.class))) throw new IllegalArgumentException("The outbound flight must land before your return flight leaves.");
                                if ("Return".equals(b.leg()) && !nw.depUtc().isAfter(rs.getObject(2, LocalDateTime.class))) throw new IllegalArgumentException("The return flight must leave after your outbound flight lands.");
                            }
                        }
                    }
                }
                // ages on the new date
                List<long[]> tickets = new ArrayList<>(); List<String> types = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT Ticket_ID, Passenger_Type, Pax_DOB FROM TICKET WHERE Booking_ID = ? AND Ticket_Status <> 'Cancelled' ORDER BY Ticket_ID")) {
                    ps.setInt(1, bookingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tickets.add(new long[]{rs.getLong(1)}); types.add(rs.getString(2));
                            if (rs.getDate(3) != null) {
                                int age = Period.between(rs.getDate(3).toLocalDate(), nw.localDate()).getYears();
                                if ("Child".equals(rs.getString(2)) && age >= 12) throw new IllegalArgumentException("A child on this booking is 12 or older on " + nw.localDate() + ". Please make a new booking.");
                            }
                        }
                    }
                }
                Set<String> used = usedSeats(c, newId); SeatCabin sc = layout(nw.capacity()).get(b.cabin());
                List<String> free = sc.seats().stream().filter(s -> !used.contains(s)).limit(tickets.size()).toList();
                if (free.size() < tickets.size()) throw new IllegalArgumentException("Not enough " + b.cabin() + " seats on that flight.");
                boolean vip = clubUntil(c, b.ownerId()) != null; double factor = CABIN_FACTOR.get(b.cabin());
                long delta = partyTotal(nw.fare(), factor, vip, b.adults(), b.children()) - partyTotal(old.fare(), factor, vip, b.adults(), b.children())
                           + (b.domestic() ? CHANGE_FEE_DOM : CHANGE_FEE_INTL) * tickets.size();
                exec(c, "UPDATE BOOKING SET Flight_ID = ? WHERE Booking_ID = ?", newId, bookingId);
                for (int i = 0; i < tickets.size(); i++) exec(c, "UPDATE TICKET SET Seat_Number = ? WHERE Ticket_ID = ?", free.get(i), tickets.get(i)[0]);
                String method = "Card";
                try (PreparedStatement ps = c.prepareStatement("SELECT Payment_Method FROM PAYMENT WHERE Booking_ID = ? ORDER BY Payment_ID LIMIT 1")) { ps.setInt(1, bookingId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) method = rs.getString(1); } }
                if (delta > 0) exec(c, "INSERT INTO PAYMENT (Booking_ID, Amount, Payment_Method, Payment_Status) VALUES (?,?,?, 'Paid')", bookingId, delta, method);
                else if (delta < 0) exec(c, "INSERT INTO PAYMENT (Booking_ID, Amount, Payment_Method, Payment_Status) VALUES (?,?,?, 'Refunded')", bookingId, -delta, method);
                c.commit();
                return obj("ok", true, "message", "Flight changed to " + nw.no() + ". " + (delta > 0 ? "You paid BDT " + delta + " (fare difference and change fee)." : delta < 0 ? "BDT " + (-delta) + " was refunded." : "No extra charge."), "seat", String.join(", ", free), "delta", delta);
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    /** like loadLeg but keeps the status and does not reject departed/cancelled flights (the caller decides) */
    static Leg loadLegAny(Connection c, int flightId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, f.Arrival_Time, f.Base_Fare, f.Status, a.Capacity, o.Country_Code, d.Country_Code "
                + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID JOIN AIRPORT o ON o.Airport_Code = f.Departure JOIN AIRPORT d ON d.Airport_Code = f.Destination WHERE f.Flight_ID = ? FOR UPDATE")) {
            ps.setInt(1, flightId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Flight not found.");
                return new Leg(flightId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class), rs.getDouble(6), rs.getInt(8),
                        "BD".equals(rs.getString(9)) && "BD".equals(rs.getString(10)), "Economy", rs.getString(7));
            }
        }
    }

    // ---- staff management ----
    static void needAdmin(Map<String, String> p) throws SQLException {
        need(p, "staff");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT Role FROM STAFF WHERE Staff_ID = ?")) {
            ps.setLong(1, Long.parseLong(p.get("_id")));
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next() || !"Admin".equals(rs.getString(1))) throw new SecurityException("Only an admin can do this."); }
        }
    }
    static boolean isAdmin(String staffId) {
        try { needAdmin(Map.of("_role", "staff", "_id", staffId)); return true; } catch (Exception e) { return false; }
    }

    static String adminFlights(Map<String, String> p) throws Exception {
        needAdmin(p);
        LocalDate date = LocalDate.parse(p.getOrDefault("date", LocalDate.now().toString()));
        String no = p.getOrDefault("flightNo", "").replace(" ", "").toUpperCase();
        ZoneId dz = zone("DAC");
        LocalDateTime s = date.atStartOfDay(dz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), e = date.plusDays(1).atStartOfDay(dz).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String sql = "SELECT f.Flight_ID, f.Flight_Number, f.Departure, f.Destination, f.Departure_Time, f.Arrival_Time, f.Status, f.Base_Fare, a.Registration, a.Capacity, "
                   + "(SELECT COUNT(*) FROM BOOKING b JOIN TICKET t ON t.Booking_ID = b.Booking_ID WHERE b.Flight_ID = f.Flight_ID AND t.Ticket_Status <> 'Cancelled') "
                   + "FROM FLIGHT f JOIN AIRCRAFT a ON a.Aircraft_ID = f.Aircraft_ID WHERE " + (no.isEmpty() ? "f.Departure_Time >= ? AND f.Departure_Time < ?" : "REPLACE(f.Flight_Number,' ','') = ? AND f.Departure_Time >= ? ")
                   + " ORDER BY f.Departure_Time LIMIT 300";
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (no.isEmpty()) { ps.setObject(1, s); ps.setObject(2, e); } else { ps.setString(1, no); ps.setObject(2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1)); }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ZonedDateTime sd = rs.getObject(5, LocalDateTime.class).atZone(ZoneOffset.UTC), sa = rs.getObject(6, LocalDateTime.class).atZone(ZoneOffset.UTC);
                    out.add(obj("id", rs.getInt(1), "no", rs.getString(2), "from", rs.getString(3), "to", rs.getString(4), "dep", fullLocal(sd, rs.getString(3)), "arr", fullLocal(sa, rs.getString(4)),
                            "status", rs.getString(7), "fare", Math.round(rs.getDouble(8)), "reg", rs.getString(9), "capacity", rs.getInt(10), "booked", rs.getInt(11)));
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    static String adminFlightUpdate(Map<String, String> p) throws Exception {
        needAdmin(p);
        int fid = Integer.parseInt(req(p, "flightId"));
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                String cur; try (PreparedStatement ps = c.prepareStatement("SELECT Status FROM FLIGHT WHERE Flight_ID = ? FOR UPDATE")) { ps.setInt(1, fid); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new NoSuchElementException("Flight not found."); cur = rs.getString(1); } }
                String msg = "Saved.";
                String fare = p.get("fare");
                if (fare != null && !fare.isBlank()) { long f = Long.parseLong(fare.trim()); if (f < 500 || f > 1_000_000) throw new IllegalArgumentException("Fare must be between 500 and 1,000,000 BDT."); exec(c, "UPDATE FLIGHT SET Base_Fare = ? WHERE Flight_ID = ?", f, fid); msg = "Fare saved. New bookings use it; existing bookings keep their price."; }
                String shift = p.get("shiftMinutes");
                if (shift != null && !shift.isBlank()) { int m = Integer.parseInt(shift.trim()); if (Math.abs(m) > 1440) throw new IllegalArgumentException("Shift at most 24 hours."); exec(c, "UPDATE FLIGHT SET Departure_Time = DATE_ADD(Departure_Time, INTERVAL ? MINUTE), Arrival_Time = DATE_ADD(Arrival_Time, INTERVAL ? MINUTE) WHERE Flight_ID = ?", m, m, fid); msg = "Flight time moved by " + m + " minutes."; }
                String st = p.get("status");
                if (st != null && !st.isBlank()) {
                    if (!Set.of("Scheduled", "Delayed", "Boarding", "Departed", "Arrived", "Cancelled").contains(st)) throw new IllegalArgumentException("Invalid status.");
                    exec(c, "UPDATE FLIGHT SET Status = ? WHERE Flight_ID = ?", st, fid); msg = "Status set to " + st + ".";
                    if ("Cancelled".equals(st) && !"Cancelled".equals(cur)) {
                        exec(c, "INSERT INTO PAYMENT (Booking_ID, Amount, Payment_Method, Payment_Status) SELECT b.Booking_ID, SUM(CASE x.Payment_Status WHEN 'Paid' THEN x.Amount WHEN 'Refunded' THEN -x.Amount ELSE 0 END), MAX(x.Payment_Method), 'Refunded' "
                                + "FROM BOOKING b JOIN PAYMENT x ON x.Booking_ID = b.Booking_ID WHERE b.Flight_ID = ? AND b.Booking_Status = 'Confirmed' GROUP BY b.Booking_ID HAVING SUM(CASE x.Payment_Status WHEN 'Paid' THEN x.Amount WHEN 'Refunded' THEN -x.Amount ELSE 0 END) > 0", fid);
                        exec(c, "UPDATE TICKET t JOIN BOOKING b ON b.Booking_ID = t.Booking_ID SET t.Ticket_Status = 'Cancelled' WHERE b.Flight_ID = ? AND b.Booking_Status = 'Confirmed'", fid);
                        exec(c, "UPDATE BOOKING SET Booking_Status = 'Cancelled' WHERE Flight_ID = ? AND Booking_Status = 'Confirmed'", fid);
                        msg = "Flight cancelled. All bookings on it were cancelled and refunded.";
                    }
                }
                c.commit(); return obj("ok", true, "message", msg);
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    static String adminFlightAdd(Map<String, String> p) throws Exception {
        needAdmin(p);
        String no = req(p, "flightNo").toUpperCase().replaceAll("\\s+", " ");
        if (!no.matches("[A-Z0-9]{2} ?\\d{1,4}")) throw new IllegalArgumentException("Flight number looks like AN 2001.");
        if (!no.contains(" ")) no = no.substring(0, 2) + " " + no.substring(2);
        String from = airport(p.get("from")), to = airport(p.get("to"));
        if (from.equals(to)) throw new IllegalArgumentException("Choose two different airports.");
        LocalDateTime local = LocalDateTime.parse(req(p, "depLocal"));
        int dur = Integer.parseInt(req(p, "duration")), days = pi(p.get("days"), 1), aircraft = Integer.parseInt(req(p, "aircraftId"));
        long fare = Long.parseLong(req(p, "fare"));
        if (dur < 20 || dur > 1500) throw new IllegalArgumentException("Duration must be 20 to 1500 minutes."); if (days < 1 || days > 90) throw new IllegalArgumentException("Repeat for 1 to 90 days.");
        if (fare < 500 || fare > 1_000_000) throw new IllegalArgumentException("Fare must be between 500 and 1,000,000 BDT.");
        int made = 0;
        try (Connection c = conn()) {
            for (String ap : new String[]{from, to}) try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM AIRPORT WHERE Airport_Code = ?")) { ps.setString(1, ap); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Unknown airport " + ap + "."); } }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM AIRCRAFT WHERE Aircraft_ID = ?")) { ps.setInt(1, aircraft); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Unknown aircraft."); } }
            for (int d = 0; d < days; d++) {
                LocalDateTime dep = local.plusDays(d).atZone(zone(from)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO FLIGHT (Aircraft_ID, Flight_Number, Departure, Destination, Departure_Time, Arrival_Time, Status, Base_Fare) VALUES (?,?,?,?,?,?, 'Scheduled', ?)")) {
                    ps.setInt(1, aircraft); ps.setString(2, no); ps.setString(3, from); ps.setString(4, to); ps.setObject(5, dep); ps.setObject(6, dep.plusMinutes(dur)); ps.setLong(7, fare);
                    made += ps.executeUpdate();
                }
            }
        }
        return obj("ok", true, "message", made + " flight(s) added" + (made < days ? " (" + (days - made) + " already existed)." : "."));
    }

    static String adminAircraft(Map<String, String> p) throws Exception {
        needAdmin(p);
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT Aircraft_ID, Model, Registration, Capacity, (SELECT COUNT(*) FROM FLIGHT f WHERE f.Aircraft_ID = a.Aircraft_ID) FROM AIRCRAFT a ORDER BY Aircraft_ID")) {
            while (rs.next()) out.add(obj("id", rs.getInt(1), "model", rs.getString(2), "reg", rs.getString(3), "capacity", rs.getInt(4), "flights", rs.getInt(5)));
        }
        return "[" + String.join(",", out) + "]";
    }

    static String adminAircraftAdd(Map<String, String> p) throws Exception {
        needAdmin(p);
        String model = req(p, "model"), reg = req(p, "registration").toUpperCase();
        int cap = Integer.parseInt(req(p, "capacity"));
        if (cap < 30 || cap > 600) throw new IllegalArgumentException("Seats must be between 30 and 600.");
        if (!reg.matches("[A-Z0-9\\-]{3,12}")) throw new IllegalArgumentException("Registration looks like S2-ANX.");
        long id = insert("INSERT INTO AIRCRAFT (Airline_ID, Model, Capacity, Registration) VALUES (1,?,?,?)", model, cap, reg);
        return obj("ok", true, "message", "Aircraft " + reg + " added (id " + id + ").");
    }


    static String airport(String s) {
        if (s == null || !s.matches("[A-Za-z]{3}")) throw new IllegalArgumentException("Use a 3-letter airport code.");
        return s.toUpperCase();
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null ? def : v;
    }
}
