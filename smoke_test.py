"""Aeronova smoke test. Start the server first, then run:   python tests/smoke_test.py
Optional: python tests/smoke_test.py http://localhost:8080 "Wasi Sadaat" 1095
Creates a throw-away test account and test bookings (they are cancelled at the end)."""
import sys, json, time, urllib.request, urllib.parse, http.cookiejar, datetime

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
STAFF = (sys.argv[2], sys.argv[3]) if len(sys.argv) > 3 else ("Wasi Sadaat", "1095")
passed = failed = 0

class Client:
    def __init__(self):
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def call(self, path, data=None):
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        try:
            r = self.op.open(urllib.request.Request(BASE + path, data=body), timeout=20)
            return r.status, json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            try: return e.code, json.loads(e.read().decode())
            except Exception: return e.code, {}

def check(name, cond, extra=""):
    global passed, failed
    if cond: passed += 1; print("  ok   ", name)
    else: failed += 1; print("  FAIL ", name, extra)

day = lambda n: (datetime.date.today() + datetime.timedelta(days=n)).isoformat()
u = Client(); s = Client(); other = Client()
tag = str(int(time.time()))[-7:]
email = f"smoke{tag}@test.com"; passport = "SM" + tag

print("Accounts")
st, d = u.call("/api/register", dict(name="Smoke Tester", email=email, passport=passport, password="secret1"))
check("register", st == 200 and d.get("loggedIn"), d)
st, d = other.call("/api/register", dict(name="Other Person", email="o" + email, passport="OT" + tag, password="secret1"))
st, d = s.call("/api/login", dict(type="staff", email=STAFF[0], password=STAFF[1]))
check("staff sign in", st == 200, d)

print("Search: three cabins with own price and seats")
st, fl = u.call("/api/flights?" + urllib.parse.urlencode(dict(origin="DAC", destination="LHR", departDate=day(20), adults=2, children=1)))
check("flights found", st == 200 and len(fl) > 0)
cab = {c["name"]: c for c in fl[0]["cabins"]}
check("3 cabins", set(cab) == {"Economy", "Premium", "Royal Suite"})
check("prices rise with cabin", cab["Economy"]["price"] < cab["Premium"]["price"] < cab["Royal Suite"]["price"])
check("own seat counts", len({c["seatsTotal"] for c in cab.values()}) == 3)
st, back = u.call("/api/flights?" + urllib.parse.urlencode(dict(origin="LHR", destination="DAC", departDate=day(28), adults=2, children=1)))
check("return flights found", st == 200 and len(back) > 0)

def pax(extra=None):
    d = dict(adults=2, children=1, method="bKash",
             p0_name="Smoke Tester", p0_dob="1990-01-01", p0_passport=passport,
             p1_name="Second Adult", p1_dob="1992-02-02", p1_passport="SA" + tag,
             p2_name="Little Kid", p2_dob="2018-03-03", p2_passport="LK" + tag)
    d.update(extra or {}); return d
out_id, ret_id = fl[0]["id"], back[0]["id"]

print("Validation")
st, d = u.call("/api/book", pax(dict(flightId=out_id, p2_dob="2010-01-01")))
check("child aged 16 refused", st == 400 and "Children must be 2 to 11" in d.get("error", ""), d)
st, d = u.call("/api/book", pax(dict(flightId=out_id, p2_dob=day(-300))))
check("infant refused", st == 400 and "infants" in d.get("error", "").lower(), d)
st, d = u.call("/api/book", pax(dict(flightId=out_id, p0_passport="")))
check("passport needed on international", st == 400, d)
st, d = u.call("/api/book", pax(dict(flightId=out_id, cabin="Economy", p0_seat="1A")))
check("royal seat refused in economy", st == 400 and "not in Economy" in d.get("error", ""), d)
st, d = u.call("/api/book", pax(dict(flightId=ret_id, retFlightId=out_id)))
check("return before outbound refused", st == 400, d)

print("Round trip booking")
st, d = u.call("/api/book", pax(dict(flightId=out_id, cabin="Economy", retFlightId=ret_id, retCabin="Premium", p0_seat="30A", p0_bag=10, p0_meal="Halal", p0_ins=1)))
check("round trip booked", st == 200 and d.get("trip") == "round" and len(d["legs"]) == 2, d)
ids = d["bookingIds"]; b1 = d["legs"][0]["bookingId"]
expected = (cab["Economy"]["total"] + 10 * 450 + 900) + 0
check("price = fares + add-ons", d["legs"][0]["amount"] == expected, (d["legs"][0]["amount"], expected))
st, d2 = other.call("/api/book", dict(flightId=out_id, cabin="Economy", adults=1, p0_name="Other Person", p0_dob="1985-05-05", p0_passport="OT" + tag, p0_seat="30A", method="Card"))
check("same seat refused for someone else", st == 400 and "taken" in d2.get("error", ""), d2)
st, m = u.call("/api/mybookings")
check("my bookings has both legs", sum(1 for b in m if str(b["bookingId"]) in ids.split(",")) == 2)
st, e = u.call("/api/eticket?ids=" + ids)
toks = [t["token"] for b in e for t in b["tickets"]]
check("6 boarding passes with QR tokens", st == 200 and len(toks) == 6)
st, x = other.call("/api/eticket?ids=" + ids)
check("other user cannot open my ticket", st == 404)

print("Staff desk")
st, b = s.call("/api/booking?id=" + str(b1))
check("staff sees all 3 passengers", st == 200 and len(b["passengers"]) == 3)
st, b = s.call("/api/booking?id=" + urllib.parse.quote("AN1|" + toks[1]))
check("QR text finds the booking", st == 200 and b["bookingId"] == b1)
st, x = s.call("/api/booking?id=2.deadbeef00")
check("forged QR refused", st == 400)
st, x = s.call("/api/staff/checkin", dict(all=1, bookingId=b1))
check("check in all", st == 200 and "3 passengers" in x.get("message", ""), x)
comp = b["passengers"][1]
st, x = s.call("/api/staff/security", dict(passengerId=b["passengerId"], ticketId=comp["ticketId"], result="Cleared"))
check("security check is saved under the companion's name", st == 200 and comp["name"] in x.get("message", ""), x)
st, x = s.call("/api/staff/security", dict(passengerId=b["passengerId"], ticketId=999999999, result="Cleared"))
check("security check for a ticket that is not on this booking is refused", st == 400, x)

print("Change flight, cancel")
st, x = u.call("/api/cancel", dict(bookingId=b1))
check("cannot cancel after check-in", st == 400)
rb = d["bookingIds"] if False else ids.split(",")[1]
st, x = u.call("/api/checkin/online", dict(bookingId=rb))
check("online check-in is refused before it opens", st == 400 and "opens" in x.get("error", ""), x)
st, jr = u.call(f"/api/journey?bookingId={rb}")
check("trip details say online check-in is not open yet", st == 200 and jr["checkinOpen"] is False and jr["reference"], jr)
st, opt = u.call(f"/api/reschedule/options?bookingId={rb}&date={day(28)}")
alt = [f for f in opt.get("flights", []) if f["ok"]]
if alt:
    st, x = u.call("/api/reschedule", dict(bookingId=rb, newFlightId=alt[0]["id"]))
    check("return flight changed", st == 200, x)
st, x = u.call("/api/cancel", dict(bookingId=rb))
check("return leg cancelled and refunded", st == 200, x)
st, m = u.call("/api/mybookings")
check("net paid is 0 after refund", [b for b in m if str(b["bookingId"]) == rb][0]["netPaid"] == 0)

print("Lounges, trip details, NovaClub receipt, sign out everywhere")
st, lg = u.call("/api/lounges")
check("lounges are listed with who can use them", st == 200 and len(lg) >= 2 and all(l.get("who") for l in lg), lg)
st, j = u.call(f"/api/journey?bookingId={b1}")
check("trip details show every passenger as checked in", st == 200 and len(j["tickets"]) == 3 and all(x["checkedIn"] for x in j["tickets"]), j)
check("lounge text is part of every passenger's trip details", all("lounge" in x for x in j["tickets"]))
st, x = u.call("/api/club/join", dict(plan="Monthly", method="bKash"))
check("NovaClub receipt has member number and receipt number", st == 200 and x.get("memberNo", "").startswith("NC-") and x.get("receipt", "").startswith("NCR-"), x)
st, x = u.call("/api/club")
check("membership is active after subscribing", st == 200 and x["member"] is True and x["memberNo"], x)
st, x = u.call("/api/logout/all", {})
check("sign out of all devices works", st == 200, x)
st, x = u.call("/api/me")
check("signed out after sign out of all devices", st == 200 and x.get("loggedIn") is False, x)

print("\n%d passed, %d failed" % (passed, failed))
sys.exit(1 if failed else 0)
