# Aeronova Airlines (final project)

Browser (HTML, CSS, JavaScript) → Java server (`HttpServer`) → JDBC → MySQL (XAMPP).

## 1. Install once

1. JDK 17 or newer (https://adoptium.net).
2. NetBeans (Maven support is built in).
3. XAMPP (https://www.apachefriends.org).

## 2. Create the database (first time)

1. Open XAMPP Control Panel and click **Start** next to **MySQL** (Apache is not needed).
2. Open http://localhost/phpmyadmin, click the **Import** tab.
3. **Choose File** → `sql\schema.sql` → scroll down → **Import**.
4. The left list now shows the `aeronova` database (13 tables, about 30,000 flights for the next 150 days).

Running `schema.sql` again deletes everything and rebuilds it. To only add flights for the next 150 days without deleting anything, import `sql\extend_flights.sql` instead.

## 3. Open and run the project

1. NetBeans → **File > Open Project** → choose this `AeronovaAirlines` folder (the one containing `pom.xml`).
2. Right-click the project → **Clean and Build** (first time it downloads the database driver). Wait for **BUILD SUCCESS**.
3. Right-click the project → **Run**. The Output window must say `Aeronova is running at http://localhost:8080`.
4. Open http://localhost:8080 in Chrome.

If Output says `Address already in use`, an old copy is still running. In Command Prompt run:

```
for /f "tokens=5" %a in ('netstat -ano ^| findstr ":8080 " ^| findstr LISTENING') do taskkill /PID %a /F
```

then Run again.

## 4. Using the website

* **SIGN IN** (top right) → choose **Passenger** or **Staff**. Passengers can also **Create account**.
* Choose **Domestic flights** (Dhaka and 7 other Bangladesh airports) or **International flights** (81 airports in 46 countries, grouped by country), pick a date and search.
* Booking needs a passenger account. **MY BOOKINGS** shows bookings and lets you cancel (refund).
* Staff sign-in opens the **Staff desk**: booking lookup, check-in, security, immigration, lounge entry and the daily report.

Staff sign in with their **name** (or email) and their **staff ID as the password**: Wasi Sadaat, Nabil Mahmud, Fariba Afrin. The IDs are set in `migrate()` in `AeronovaAirlines.java` (array `team`).

## 5. Settings you may change

| What | Where |
|---|---|
| Database user and password | `AeronovaAirlines.java`: `env("DB_USER", "root")` and `env("DB_PASS", "")` |
| Port | `env("PORT", "8080")` |
| Default staff password | environment variable `STAFF_PASSWORD` (set before the first run on a new database) |
| Destination photos | put `LHR.jpg`, `DXB.jpg`, `SIN.jpg`, `JFK.jpg`, `IST.jpg`, `HND.jpg` in the `web\images` folder (Ctrl+F5 to refresh). Without them the built-in artwork shows |
| Featured destinations | `AIRPORT.Featured` column in phpMyAdmin (1 = show) |
| Fares, flight times, seats | `AIRPORT` and `FLIGHT` tables in phpMyAdmin |

## 6. Share a public link (temporary)

The site only runs while your computer, XAMPP MySQL and the Run window are open. To let someone else open it:

1. Download `cloudflared-windows-amd64.exe` from https://github.com/cloudflare/cloudflared/releases and rename it to `cloudflared.exe`.
2. In Command Prompt, in that folder, run: `cloudflared.exe tunnel --url http://localhost:8080`
3. Share the `https://....trycloudflare.com` address it prints. It changes every time.

Before sharing, change the staff password (see section 5). A permanent domain needs a hosting server and a paid domain name.

## 7. Folder map

```
pom.xml                          Maven settings and the MariaDB driver
src/main/java/.../AeronovaAirlines.java   the whole Java server
web/index.html                   website
web/staff.html                   staff desk
web/ticket.html                  e-ticket / boarding pass (QR codes, print or save as PDF)
web/qrcode.js                    QR code library (MIT licence, qrcode-generator 1.4.4)
web/images/                      optional destination photos
sql/schema.sql                   full database (tables and data)
sql/extend_flights.sql           adds the next 150 days of flights
```

Times are stored in UTC. Search results, My Bookings, tickets and the staff desk show each airport's local time. Payments are simulated.

## 8. Update v2 (airport search, more flights, seats, NovaClub)

* **Existing database:** import `sql\upgrade_v2.sql` once in phpMyAdmin (keeps bookings and accounts). A fresh `schema.sql` already includes this.
* From / To have a search box above the list: type an airport, city or code.
* Every route has 2 or 3 flights a day. Each flight shows "N seats available" or "Not available".
* NovaClub needs a paid subscription (simulated payment). Members get 10% off fares and +10 kg baggage, and staff see a VIP flag. Prices and discount: `CLUB_MONTHLY`, `CLUB_YEARLY`, `VIP_DISCOUNT` at the top of `AeronovaAirlines.java`.

## 9. Update v3

* From / To are type-ahead boxes: type `A` and countries and cities starting with A appear first; type `au` and Australia comes up.
* Flight status shows the route, local departure and arrival times, estimated and boarding time, terminal, gate, baggage belt, aircraft, transit stop and a progress bar. Terminal, gate and belt are generated demo values (they are not stored in the database).
* Passenger reviews (sign in to post) and a helpline box. Change the phone and email in `web/index.html` (search for `Helpline`).
* Staff sign-in uses name or email plus staff ID.

## 9. Update v3 (passengers, cabin colours, 5 months of flights)

* Search has **Adults (12+)** and **Children (2-11, half fare)**, up to 9 passengers per booking, at least 1 adult. Infants under 2 are not booked online. One booking makes one ticket and seat per passenger; the price shown is the total.
* Economy, Premium and Royal Suite cards now share the same colour.
* Flights now exist for the next **150 days** and the date pickers stop at that limit. Import `sql\upgrade_v2.sql` (safe to run again, keeps bookings) to load them; it takes a little longer than before.
* (Replaced in v5: the staff desk now lists every passenger on the booking and can check in one or all.)

## 10. Update v5 (round trip, passenger details, seat map, cabins, e-ticket)

No SQL file to import: the server adds the new columns by itself the first time it starts (`migrate()`), and your existing bookings are kept. A fresh `schema.sql` already has them.

* **Round trip works.** Search shows the outbound flights; after you pick one, the return flights for the return date appear (step 2 of 2). Each flight is its own booking (linked by `Group_Ref`), so one leg can be cancelled alone.
* **Three cabins on every flight.** Each flight card shows Economy, Premium and Royal Suite with their own price, their own seats left, baggage and meal. The old Cabin drop-down is gone.
* **Seats per cabin.** The plan comes from the aircraft capacity (function `layout()` in `AeronovaAirlines.java`): Royal Suite in front, then Premium, then Economy, and the total always equals the capacity. Example, Boeing 787-9 (290 seats): Royal Suite 8, Premium 30, Economy 252. Change the row counts inside `layout()`.
* **Passenger details.** Every traveller needs a full name and date of birth; passport is required on international flights. The **server checks the age on the travel date** (adult 12+, child 2-11, infants refused, children need an 18+ passenger).
* **Seat map.** Pick seats for each passenger and each flight; skipped seats are assigned automatically. A seat that was taken a second ago is refused and the map refreshes.
* **E-ticket.** After booking (and from MY BOOKINGS) open `/ticket.html`: one boarding pass per passenger with a QR code. Use Print / Save as PDF.
* **QR security.** The QR holds `AN1|<ticketId>.<signature>`. Set your own secret before going public: environment variable `QR_SECRET`.
* **Staff desk.** Look up by booking ID or paste the QR text. All passengers are listed; check in one or everyone.
* **Local time** is used in search, My Bookings, tickets and staff desk. The date you pick is the date at the departure airport.

## 11. Domain and hosting (what to do next)

1. **Free test link:** section 6 (Cloudflare quick tunnel). Changes each time.
2. **Own domain, still on your PC:** buy a domain, add it to a free Cloudflare account, and make a *named tunnel* to `http://localhost:8080`. The address stays the same, but the site is off when your PC is off.
3. **Always on:** rent a small server (VPS), install Java 17 and MariaDB there, run the project as a service, and put Caddy in front for automatic HTTPS. Create a new MariaDB user with a password and set `DB_USER`, `DB_PASS`, `QR_SECRET`, `STAFF_PASSWORD`. Never leave `root` without a password on a public server.

## 12. Update v6 (everything else: accounts, add-ons, change flight, staff management)

Again nothing to import: start the server and the new columns are added automatically.

* **Forgot password:** on the sign-in box click *Forgot password?* and enter the email and passport number of the account (both must match), then choose a new password. (A real e-mail link needs a mail server; this works without one.)
* **ACCOUNT** button (after sign-in): edit name, phone, nationality, gender and change the password.
* **Add-ons** in the booking window, per passenger and per flight: extra baggage (5 to 30 kg), meal choice (Standard, Vegetarian, Vegan, Halal, Diabetic, Child meal) and travel insurance. Prices: `BAG_RATE_*`, `INS_*` at the top of the add-ons section in `AeronovaAirlines.java`. Meal and extras print on the boarding pass.
* **Change flight:** MY BOOKINGS > *Change flight* (up to 4 hours before departure, not after check-in). Pick a new date and a flight on the same route; the fare difference plus a change fee (`CHANGE_FEE_DOM` 500 / `CHANGE_FEE_INTL` 1,500 per passenger) is charged or refunded. Round-trip order is kept. Cancelling always refunds the net amount paid, add-ons included.
* **Staff desk > Manage flights:** edit a fare, shift a flight time, set Delayed / Boarding / Cancelled (cancelling refunds all its bookings), add a flight (can repeat daily for up to 90 days), add aircraft, and change your own password (8+ characters). The old 4-digit staff ID still works until you change it: **change it after your first sign-in.**
* Before going public set these environment variables: `DB_USER`, `DB_PASS` (a MariaDB user with a password, not `root`), `QR_SECRET`.

## 13. Update v7 (safety and reliability)

* **Logins survive a server restart.** Sessions are kept in the database (`SESSION_STORE`, tokens are stored hashed), so passengers and staff are not signed out when you restart.
* **Staff roles.** `Admin` can manage flights, fares and aircraft; `Desk` can only look up bookings, check in, record checks and see the report. Wasi Sadaat is the Admin; change roles in phpMyAdmin (`STAFF.Role` = `Admin` or `Desk`).
* **Staff report** shows local departure times (the day is a Dhaka day).
* **Companions sit together.** When seats are not picked by hand, the passengers get seats in one row when possible.
* **Security headers** on every response. When the site runs over HTTPS set the environment variable `COOKIE_SECURE=1` (adds the Secure cookie flag and HSTS).
* **Smoke test.** With the server running: `python tests/smoke_test.py` (needs Python 3, nothing to install). It makes a test account and checks search, three cabins, age rules, round trip, seat conflicts, add-ons, e-ticket, staff check-in, change flight and refund. Expect `26 passed, 0 failed`. Run it after every change you make.

## 12. Update v8 (checks recorded for the right passenger)

* On the Staff desk, a **"Record security, immigration and lounge entry for"** list picks which passenger on the booking the check is for (the scanned passenger is chosen automatically). The record stores that ticket, and the confirmation names the passenger, so a companion's check is no longer saved only under the account holder.
* A ticket that is not on the booking is refused. The columns are added to the database automatically the first time the server starts.
* `tests/smoke_test.py` has two new checks for this.

## 13. Update v9 (airport side shown on the website, NovaClub confirmation)

* **Lounges on the website:** a Lounges section lists the lounges from the database (Nova Royal Lounge for Royal Suite, Nova Premium Lounge for Premium and NovaClub VIP). The cabin cards, the booking confirmation, My bookings and the e-ticket now show lounge access.
* **Trip details (My bookings > Trip details):** booking reference and booking date, then for every passenger: booked, checked in (online or at the desk), security result, immigration result (international only) and lounge visits. These are the records the staff desk saves in the CHECK_IN, SECURITY_CHECK, IMMIGRATION and LOUNGE_ACCESS tables. The e-ticket page shows the same progress.
* **Online check-in:** opens 48 hours before departure and closes 60 minutes before (`CHECKIN_OPEN_HOURS`, `CHECKIN_CLOSE_MINUTES` in the Java file). It writes to CHECK_IN with channel Online. The column Staff_ID is made optional automatically at start-up.
* **Staff rules:** lounge entry is refused if the ticket's cabin has no access to that lounge; immigration is refused for domestic flights.
* **NovaClub:** the browser alert is gone. After paying, a membership card with member number, plan, validity and a receipt is shown. Each payment is saved in the new CLUB_PAYMENT table.
* All other pop-up boxes are now in-page boxes. Profile has **Sign out of all devices**.
* `tests/smoke_test.py` has new checks for these.
