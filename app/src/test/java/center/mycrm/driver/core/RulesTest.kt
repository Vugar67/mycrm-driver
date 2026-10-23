package center.mycrm.driver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RulesTest {

    // ---- money ----

    @Test
    fun cashOnDeliveryCollectsTotal() {
        assertEquals(1250.0, Rules.cashToCollect(1250.0, 1200.0, 50.0, 0.0, "Cash on delivery"), 0.001)
    }

    @Test
    fun cashUsesFormulaWhenTotalMissing() {
        assertEquals(1150.0, Rules.cashToCollect(null, 1200.0, 50.0, 100.0, "Cash on delivery"), 0.001)
    }

    @Test
    fun cashNeverNegative() {
        assertEquals(0.0, Rules.cashToCollect(null, 0.0, 0.0, 50.0, "Cash on delivery"), 0.001)
    }

    @Test
    fun paidStatusesCollectNothing() {
        for (s in listOf("Paid cash", "Paid via transfer", "Paid via link", "Paid via Tabby/Tamara", "Accounts", "Crypto", "Gift", "Replacement")) {
            assertEquals(s, 0.0, Rules.cashToCollect(900.0, 900.0, 0.0, 0.0, s), 0.001)
            assertEquals(s, Rules.PaymentMode.NONE, Rules.paymentMode(s))
        }
    }

    @Test
    fun unknownStatusesAreNeverNothingToCollect() {
        // v1.0.1 (ORD-123330): anything not known as settled shows the amount in red
        for (st in listOf("Pending", "Unpaid", "Not paid", "Cash/Card", "TBD", "x")) {
            assertEquals(st, Rules.PaymentMode.UNKNOWN, Rules.paymentMode(st))
        }
        assertEquals(1500.0, Rules.amountDue(1500.0, null, null, null), 0.001)
    }

    @Test
    fun anyOnDeliveryIsCollectedLikeTheCore() {
        assertEquals(Rules.PaymentMode.CASH, Rules.paymentMode("Card on delivery"))
        assertEquals(Rules.PaymentMode.CASH, Rules.paymentMode("Cash  on   delivery"))
        assertEquals(Rules.PaymentMode.CASH, Rules.paymentMode("COD"))
        assertEquals(Rules.PaymentMode.LINK, Rules.paymentMode("Link on delivery"))
    }

    @Test
    fun linkOnDeliveryIsNotCash() {
        assertEquals(Rules.PaymentMode.LINK, Rules.paymentMode("Link on delivery"))
        assertEquals(0.0, Rules.cashToCollect(900.0, 900.0, 0.0, 0.0, "Link on delivery"), 0.001)
        assertEquals(900.0, Rules.amountDue(900.0, 900.0, 0.0, 0.0), 0.001)
    }

    @Test
    fun emptyPaymentStatusShowsAmountAsUnknown() {
        assertEquals(Rules.PaymentMode.UNKNOWN, Rules.paymentMode(null))
        assertEquals(Rules.PaymentMode.UNKNOWN, Rules.paymentMode("  "))
        assertEquals(0.0, Rules.cashToCollect(700.0, null, null, null, null), 0.001)
        assertEquals(700.0, Rules.amountDue(700.0, null, null, null), 0.001)
    }

    @Test
    fun amountFromItemsLikeTheCore() {
        // core: Σ(price × qty) + delivery − discount, qty defaults to 1, price to 0
        val sub = Rules.itemsSubtotal(listOf(450.0 to 2.0, 100.0 to null, null to 3.0))
        assertEquals(1000.0, sub, 0.001)
        assertEquals(1030.0, Rules.amountDue(999.0, 999.0, 50.0, 20.0, sub), 0.001)
        assertEquals(1030.0, Rules.cashToCollect(999.0, 999.0, 50.0, 20.0, "Cash on delivery", sub), 0.001)
        assertEquals(0.0, Rules.cashToCollect(999.0, 999.0, 50.0, 20.0, "Link on delivery", sub), 0.001)
    }

    @Test
    fun amountRoundsToFils() {
        assertEquals(0.3, Rules.amountDue(null, null, null, null, 0.1 + 0.2), 0.0000001)
    }

    @Test
    fun failReasonsMatchTheDispatcher() {
        assertEquals(
            listOf("Customer not answering", "Customer postponed", "Customer refused", "Address problem", "Driver could not finish route", "Other…"),
            Rules.FAIL_REASONS
        )
    }

    @Test
    fun paymentModeIgnoresCaseAndSpaces() {
        assertEquals(Rules.PaymentMode.CASH, Rules.paymentMode("  cash ON delivery "))
        assertEquals(Rules.PaymentMode.LINK, Rules.paymentMode("LINK ON DELIVERY"))
    }

    @Test
    fun aedFormatting() {
        assertEquals("1,250 AED", Rules.fmtAed(1250.0))
        assertEquals("1,250.50 AED", Rules.fmtAed(1250.5))
        assertEquals("0 AED", Rules.fmtAed(0.0))
    }

    // ---- priority / sorting ----

    @Test
    fun priorityParsing() {
        assertEquals(1, Rules.priorityOf(1))
        assertEquals(2, Rules.priorityOf("2"))
        assertEquals(1, Rules.priorityOf("P1"))
        assertEquals(1, Rules.priorityOf("Urgent"))
        assertEquals(3, Rules.priorityOf(null))
        assertEquals(3, Rules.priorityOf(""))
        assertEquals(3, Rules.priorityOf(7))
        assertEquals(1, Rules.priorityOf(0))
    }

    private data class O(val num: String, val p: Int, val d: String?, val f: String?)

    @Test
    fun routeSortPriorityThenDateThenTimeThenNumber() {
        val list = listOf(
            O("ORD-5", 3, null, null),
            O("ORD-4", 2, "2026-10-17", "10:00"),
            O("ORD-3", 2, "2026-10-16", "15:00"),
            O("ORD-2", 2, "2026-10-16", "09:00"),
            O("ORD-1", 1, null, null),
            O("ORD-6", 2, null, null)
        )
        val sorted = Rules.sortForRoute(list, { it.p }, { it.d }, { it.f }, { it.num }).map { it.num }
        assertEquals(listOf("ORD-1", "ORD-2", "ORD-3", "ORD-4", "ORD-6", "ORD-5"), sorted)
    }

    // ---- phones ----

    @Test
    fun phoneFormatting() {
        assertEquals("+971 50 123 4567", Rules.formatPhone("971501234567"))
        assertEquals("+971 50 123 4567", Rules.formatPhone("0501234567"))
        assertEquals("+971 56 331 3233", Rules.formatPhone("+971 56 331 32 33"))
        assertEquals("+994502462090", Rules.formatPhone("+994502462090"))
        assertEquals("", Rules.formatPhone(null))
    }

    @Test
    fun phoneForLinks() {
        assertEquals("971501234567", Rules.phoneForLinks("0501234567"))
        assertEquals("971501234567", Rules.phoneForLinks("00971501234567"))
        assertEquals("971501234567", Rules.phoneForLinks("+971 50 123 4567"))
    }

    @Test
    fun whatsappLinkEncodesText() {
        val l = Rules.whatsappLink("0501234567", "Hi ORD-1 & co")
        assertTrue(l, l.startsWith("https://wa.me/971501234567?text="))
        assertTrue(l, l.contains("ORD-1"))
        assertFalse(l, l.contains(" "))
        assertEquals("https://wa.me/971501234567", Rules.whatsappLink("0501234567", null))
    }

    // ---- navigation ----

    @Test
    fun coordsFromGoogleMapsLinks() {
        assertEquals(25.2048 to 55.2708, Rules.coordsFrom("https://www.google.com/maps/place/X/@25.2048,55.2708,17z/data=!3m1"))
        assertEquals(25.1 to 55.3, Rules.coordsFrom("https://maps.google.com/?q=25.1,55.3"))
        assertEquals(25.1 to 55.3, Rules.coordsFrom("https://www.google.com/maps/search/?api=1&query=25.1%2C55.3"))
        assertEquals(24.4539 to 54.3773, Rules.coordsFrom("https://www.google.com/maps/place/A/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d24.4539!4d54.3773"))
        assertEquals(25.0 to 55.0, Rules.coordsFrom("geo:25.0,55.0?q=x"))
    }

    @Test
    fun noCoordsInShortLinks() {
        assertNull(Rules.coordsFrom("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(Rules.coordsFrom(""))
        assertNull(Rules.coordsFrom(null))
        assertNull(Rules.coordsFrom("https://maps.google.com/?q=0.0,0.0"))
    }

    @Test
    fun navigatePrefersLocationUrl() {
        assertEquals("https://maps.app.goo.gl/x", Rules.navigateLink(" https://maps.app.goo.gl/x ", "DUBAI", "JVC", "V12", null))
    }

    @Test
    fun navigateFallsBackToAddressSearch() {
        val l = Rules.navigateLink(null, "DUBAI", "JVC", "Villa 12", "")
        assertNotNull(l)
        assertTrue(l!!, l.startsWith("geo:0,0?q="))
        assertTrue(l, l.contains("Villa+12") && l.contains("JVC") && l.contains("DUBAI") && l.contains("UAE"))
        assertNull(Rules.navigateLink("", null, " ", null, null))
    }

    @Test
    fun wazeLink() {
        assertEquals("https://waze.com/ul?ll=25.1,55.3&navigate=yes", Rules.wazeLink(25.1, 55.3))
    }

    // ---- versions ----

    @Test
    fun versionCompare() {
        assertTrue(Rules.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(Rules.isNewerVersion("v1.1.0", "1.0.9"))
        assertTrue(Rules.isNewerVersion("v2.0.0", "1.99.99"))
        assertFalse(Rules.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(Rules.isNewerVersion("v0.9.9", "1.0.0"))
        assertTrue(Rules.isNewerVersion("v1.0.0", "0.0.0-dev"))
        assertFalse(Rules.isNewerVersion("", "1.0.0"))
        assertTrue(Rules.isNewerVersion("v1.0.10", "1.0.9"))
    }

    @Test
    fun versionCodes() {
        assertEquals(10000, Rules.versionCodeOf("v1.0.0"))
        assertEquals(10203, Rules.versionCodeOf("1.2.3"))
        assertEquals(1, Rules.versionCodeOf("0.0.0-dev"))
        assertTrue(Rules.versionCodeOf("v1.0.10") > Rules.versionCodeOf("v1.0.9"))
    }

    // ---- dates ----

    @Test
    fun todayStartInDubai() {
        // 16.10.2026 02:30 Dubai = 15.10.2026 22:30 UTC → today starts 15.10 20:00 UTC
        val now = Instant.parse("2026-10-15T22:30:00Z")
        assertEquals("2026-10-15T20:00:00Z", Rules.todayStartDubaiIso(now))
    }

    @Test
    fun timesShownInDubai() {
        assertEquals("14:05", Rules.fmtTimeDubai("2026-10-16T10:05:00+00:00"))
        assertEquals("14:05", Rules.fmtTimeDubai("2026-10-16T10:05:00.123456+00:00"))
        assertEquals("14:05", Rules.fmtTimeDubai("2026-10-16T10:05:00Z"))
        assertEquals("", Rules.fmtTimeDubai(null))
        assertEquals("", Rules.fmtTimeDubai("garbage"))
    }

    @Test
    fun scheduleText() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("Today 14:00–16:00", Rules.fmtSchedule("2026-10-16", "14:00:00", "16:00", today))
        assertEquals("Tomorrow from 10:00", Rules.fmtSchedule("2026-10-17", "10:00", null, today))
        assertEquals("20.10", Rules.fmtSchedule("2026-10-20", null, null, today))
        assertEquals("until 18:00", Rules.fmtSchedule(null, "", "18:00", today))
        assertEquals("", Rules.fmtSchedule(null, null, null, today))
    }

    @Test
    fun pieces() {
        assertEquals("1 pc", Rules.pcs(1.0))
        assertEquals("2 pcs", Rules.pcs(2.0))
        assertEquals("1.5 pcs", Rules.pcs(1.5))
    }

    @Test
    fun addressText() {
        assertEquals("Villa 3, Apt 4, JVC, DUBAI", Rules.addressText("DUBAI", "JVC", "Villa 3", "Apt 4"))
        assertEquals("ABU DHABI", Rules.addressText("ABU DHABI", null, " ", ""))
    }

    // ---- photo at the door ----

    @Test
    fun podStamp() {
        val at = Instant.parse("2026-10-18T10:32:00Z").toEpochMilli()   // 14:32 Dubai
        assertEquals(listOf("ORD-123330 · Rufat", "18.10.2026 14:32 Dubai", "GPS 25.20480, 55.27080 ±12 m"),
            Rules.podStampLines("ORD-123330", "Rufat", at, 25.2048, 55.2708, 12.4f))
        assertEquals("GPS: off", Rules.podStampLines("ORD-1", "", at, null, null, null)[2])
        assertEquals("ORD-1", Rules.podStampLines("ORD-1", " ", at, null, null, null)[0])
        assertEquals("GPS 25.10000, 55.30000", Rules.podStampLines("ORD-1", "R", at, 25.1, 55.3, null)[2])
    }

    @Test
    fun podObjectNameMatchesTheBucketPolicy() {
        val at = Instant.parse("2026-10-18T10:32:05Z").toEpochMilli()
        val n = Rules.podObjectName("1F0E9C2A-1234-4ABC-9DEF-00112233AABB", at, 0x00ab)
        assertEquals("1f0e9c2a-1234-4abc-9def-00112233aabb/20261018-143205-00ab.jpg", n)
        assertTrue(n, Rules.POD_OBJECT_NAME.matches(n))
        assertTrue(Rules.POD_OBJECT_NAME.matches(Rules.podObjectName("1f0e9c2a-1234-4abc-9def-00112233aabb", at, 0x1FFFF)))
        assertFalse(Rules.POD_OBJECT_NAME.matches("1f0e9c2a-1234-4abc-9def-00112233aabb/../x.jpg"))
        assertFalse(Rules.POD_OBJECT_NAME.matches("1f0e9c2a-1234-4abc-9def-00112233aabb/x.exe"))
    }

    // ---- WhatsApp messages (v1.2) ----

    private val gen1 = MessageTemplate(1, "On my way", "Hello {client}, this is {driver} from {seller}. Order {order}.", null, 10)
    private val gen2 = MessageTemplate(2, "At the door", "I'm at the door with {order}.", null, 20)
    private val gen3 = MessageTemplate(3, "Send location", "Please send your location.", "", 30)
    private val glow1 = MessageTemplate(4, "on my  WAY", "GLOW: on the way with {order}", " glow clinic ", 5)
    private val glow2 = MessageTemplate(5, "Thanks", "Thank you from GLOW", "GLOW Clinic", 50)

    @Test
    fun pickMessagesGeneralOnly() {
        assertEquals(listOf(1L, 2L, 3L), Rules.pickMessages(listOf(gen2, gen3, gen1, glow1, glow2), "Other Seller").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), Rules.pickMessages(listOf(gen1, gen2, gen3, glow1), null).map { it.id })
    }

    @Test
    fun sellerMessageReplacesTheSameTitle() {
        // GLOW's "on my way" replaces the general one; its own "Thanks" is added; sorted by sort_order
        assertEquals(listOf(4L, 2L, 3L, 5L), Rules.pickMessages(listOf(gen1, gen2, gen3, glow1, glow2), "GLOW CLINIC").map { it.id })
    }

    @Test
    fun noMessagesFallsBackToTheBuiltInText() {
        val l = Rules.pickMessages(emptyList(), "X")
        assertEquals(1, l.size)
        assertEquals("Hello, this is the Pure Peptides driver. I'm on my way with your order ORD-1.",
            Rules.fillMessage(l[0].body, mapOf("seller" to "Pure Peptides", "order" to "ORD-1")))
    }

    @Test
    fun fillMessagePlaceholders() {
        val v = mapOf("client" to "Ahmed", "order" to "ORD-123456", "driver" to "Rufat", "seller" to "GLOW Clinic",
            "amount" to "880 AED", "window" to "Today 14:00–16:00", "address" to "Villa 12, JVC, DUBAI")
        assertEquals("Hello Ahmed, this is Rufat from GLOW Clinic. Order ORD-123456.", Rules.fillMessage(gen1.body, v))
        assertEquals("Pay 880 AED. Arriving Today 14:00–16:00 at Villa 12, JVC, DUBAI.",
            Rules.fillMessage("Pay {AMOUNT}. Arriving {Window} at {address}.", v))
        assertEquals("Keep {unknown} as is", Rules.fillMessage("Keep {unknown} as is", v))
    }

    @Test
    fun fillMessageCleansEmptyValues() {
        assertEquals("Hello, this is Rufat.", Rules.fillMessage("Hello {client}, this is {driver}.", mapOf("client" to "", "driver" to "Rufat")))
        assertEquals("Line one\nLine two (ORD-1)", Rules.fillMessage("  Line one  \n Line two ( {order} ) ", mapOf("order" to "ORD-1")))
    }

    // ---- cash taken at the door (v1.3) ----

    @Test
    fun parseCashAcceptsWhatDriversType() {
        assertEquals(880.0, Rules.parseCash("880")!!, 0.0001)
        assertEquals(880.5, Rules.parseCash(" 880.5 ")!!, 0.0001)
        assertEquals(880.5, Rules.parseCash("880,50")!!, 0.0001)
        assertEquals(1200.0, Rules.parseCash("1 200")!!, 0.0001)
        assertEquals(1200.0, Rules.parseCash("1,200")!!, 0.0001)
        assertEquals(1200.5, Rules.parseCash("1,200.50")!!, 0.0001)
        assertEquals(0.0, Rules.parseCash("0")!!, 0.0001)
    }

    @Test
    fun parseCashRejectsGarbage() {
        listOf(null, "", "  ", "abc", "-5", "8.8.0", "880.505", "88O", ".5", "880.", "1,20,0", "12345678").forEach {
            assertNull("should reject [$it]", Rules.parseCash(it))
        }
    }

    @Test
    fun checkCashKinds() {
        assertEquals(Rules.CashKind.EXACT, Rules.checkCash(880.0, 880.0).kind)
        assertEquals(Rules.CashKind.EXACT, Rules.checkCash(880.004, 880.0).kind)
        assertEquals(Rules.CashKind.LESS, Rules.checkCash(879.99, 880.0).kind)
        assertEquals(Rules.CashKind.OVER, Rules.checkCash(900.0, 880.0).kind)
        assertEquals(20.0, Rules.checkCash(900.0, 880.0).over, 0.0001)
        // big = more than max(100, 25 %)
        assertEquals(Rules.CashKind.OVER, Rules.checkCash(1100.0, 880.0).kind)
        assertEquals(Rules.CashKind.BIG_OVER, Rules.checkCash(1101.0, 880.0).kind)
        assertEquals(Rules.CashKind.OVER, Rules.checkCash(300.0, 200.0).kind)
        assertEquals(Rules.CashKind.BIG_OVER, Rules.checkCash(300.01, 200.0).kind)
        // the database limit: due × 2 + 1000
        assertEquals(Rules.CashKind.BIG_OVER, Rules.checkCash(2760.0, 880.0).kind)
        assertEquals(Rules.CashKind.TOO_MUCH, Rules.checkCash(2760.01, 880.0).kind)
        assertEquals(Rules.CashKind.TOO_MUCH, Rules.checkCash(8800.0, 880.0).kind)
        assertTrue(Rules.checkCash(1500.0, 880.0).canSave)
        assertFalse(Rules.checkCash(800.0, 880.0).canSave)
        assertFalse(Rules.checkCash(8800.0, 880.0).canSave)
    }

    @Test
    fun cashTakenPrefersTheEnteredAmount() {
        assertEquals(900.0, Rules.cashTaken(900.0, 880.0), 0.0001)
        assertEquals(880.0, Rules.cashTaken(null, 880.0), 0.0001)
    }

    // ---- the driver's own delivery sequence (v1.4) ----

    private data class R(val num: String, val seq: Int?, val p: Int = 3, val d: String? = null, val f: String? = null, val t: String? = null)
    private fun warnings(l: List<R>) = Rules.routeWarnings(l, { it.p }, { it.num }, { it.d }, { it.f }, { it.t })

    @Test
    fun applyRoutePlacedFirstThenTheRestInTheirOrder() {
        val l = listOf(R("A", null), R("B", 2), R("C", null), R("D", 1), R("E", 2))
        assertEquals(listOf("D", "B", "E", "A", "C"), Rules.applyRoute(l) { it.seq }.map { it.num })
        assertEquals(listOf("A", "C"), Rules.applyRoute(listOf(R("A", null), R("C", null))) { it.seq }.map { it.num })
        assertTrue(Rules.applyRoute(emptyList<R>()) { it.seq }.isEmpty())
    }

    @Test
    fun sequenceTapTogglesAndShifts() {
        var ids = emptyList<String>()
        ids = Rules.toggleInSequence(ids, "a"); ids = Rules.toggleInSequence(ids, "b"); ids = Rules.toggleInSequence(ids, "c")
        assertEquals(listOf("a", "b", "c"), ids)
        ids = Rules.toggleInSequence(ids, "a")           // mistake → a loses its number, b and c move up
        assertEquals(listOf("b", "c"), ids)
        ids = Rules.toggleInSequence(ids, "a")
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun arrowsMoveOnePositionAndStopAtTheEdges() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), Rules.moveInRoute(ids, "b", -1))
        assertEquals(listOf("a", "c", "b"), Rules.moveInRoute(ids, "b", 1))
        assertEquals(ids, Rules.moveInRoute(ids, "a", -1))
        assertEquals(ids, Rules.moveInRoute(ids, "c", 1))
        assertEquals(ids, Rules.moveInRoute(ids, "zzz", 1))
        assertEquals(listOf("a", "b", "c", "d"), Rules.addToRoute(ids, "d"))
        assertEquals(ids, Rules.addToRoute(ids, "b"))
    }

    @Test
    fun urgentNotFirstOnlyWhenANormalOrderIsBeforeIt() {
        assertNull(Rules.urgentNotFirst(listOf(R("U", 1, p = 1), R("N", 2)), { it.p }, { it.num }))
        assertNull(Rules.urgentNotFirst(listOf(R("U1", 1, p = 1), R("U2", 2, p = 1), R("N", 3)), { it.p }, { it.num }))
        assertEquals("U", Rules.urgentNotFirst(listOf(R("N", 1), R("U", 2, p = 1)), { it.p }, { it.num }))
        assertNull(Rules.urgentNotFirst(emptyList<R>(), { it.p }, { it.num }))
    }

    @Test
    fun routeWarningsUrgentAndWindows() {
        // urgent after a normal one
        assertEquals(listOf("URGENT U is #2 — deliver urgent orders first?"), warnings(listOf(R("N", 1), R("U", 2, p = 1))))
        // window broken: 14–16 placed before 10–12 on the same day
        val w = warnings(listOf(R("A", 1, d = "2026-10-21", f = "14:00:00", t = "16:00:00"), R("B", 2, d = "2026-10-21", f = "10:00", t = "12:00")))
        assertEquals(1, w.size)
        assertTrue(w[0], w[0].startsWith("A (") && w[0].contains("14:00–16:00) is before B (") && w[0].endsWith("10:00–12:00)"))
        // later date before an earlier date
        assertEquals(1, warnings(listOf(R("A", 1, d = "2026-10-22"), R("B", 2, d = "2026-10-21", f = "10:00", t = "12:00"))).size)
        // fine: earlier first; overlapping windows; missing dates / windows
        assertTrue(warnings(listOf(R("B", 1, d = "2026-10-21", f = "10:00", t = "12:00"), R("A", 2, d = "2026-10-21", f = "14:00", t = "16:00"))).isEmpty())
        assertTrue(warnings(listOf(R("A", 1, d = "2026-10-21", f = "11:00", t = "13:00"), R("B", 2, d = "2026-10-21", f = "10:00", t = "12:00"))).isEmpty())
        assertTrue(warnings(listOf(R("A", 1, d = "2026-10-21", f = "14:00", t = "16:00"), R("B", 2, d = null, f = "10:00", t = "12:00"))).isEmpty())
        assertTrue(warnings(listOf(R("A", 1, d = "2026-10-21", f = null, t = "16:00"), R("B", 2, d = "2026-10-21", f = "10:00", t = "12:00"))).isEmpty())
        assertTrue(warnings(listOf(R("A", 1, d = "2026-10-21", f = "14:00", t = "16:00"), R("B", 2, d = "2026-10-21", f = "10:00", t = null))).isEmpty())
        // at most 4 lines
        val many = (1..6).map { R("O$it", it, d = "2026-10-21", f = "1${7 - it}:00", t = "1${7 - it}:30") }   // windows go backwards
        assertEquals(4, warnings(many).size)
    }

    // ---- trips (v1.5) ----

    @Test
    fun tripKindsAndLabels() {
        assertEquals(Rules.TripKind.PICKUP, Rules.tripKind("pickup"))
        assertEquals(Rules.TripKind.DROPOFF, Rules.tripKind(" DropOff "))
        assertEquals(Rules.TripKind.TASK, Rules.tripKind("task"))
        assertEquals(Rules.TripKind.OTHER, Rules.tripKind(null))
        assertEquals("PICK UP", Rules.TripKind.PICKUP.label)
        assertEquals("1 stop", Rules.stopsText(1))
        assertEquals("3 stops", Rules.stopsText(3))
    }

    @Test
    fun tripDayNoteOverdueAndFuture() {
        val today = LocalDate.of(2026, 10, 21)
        assertNull(Rules.tripDayNote("2026-10-21", today))
        assertEquals("overdue · 19.10", Rules.tripDayNote("2026-10-19", today))
        assertEquals("22.10", Rules.tripDayNote("2026-10-22", today))
        assertNull(Rules.tripDayNote(null, today))
        assertNull(Rules.tripDayNote("garbage", today))
    }

    @Test
    fun tripsSortedByDateTimeId() {
        data class T(val id: Long, val d: String?, val t: String?)
        val list = listOf(T(5, "2026-10-21", null), T(4, "2026-10-21", "09:00:00"), T(3, "2026-10-20", "18:00"), T(2, "2026-10-21", "07:30"), T(1, "2026-10-21", "07:30"))
        val ids = Rules.sortTrips(list, { it.d }, { it.t }, { it.id }).map { it.id }
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L), ids)
    }

    @Test
    fun tripStopLinkOnlyRealLinks() {
        assertEquals("https://maps.app.goo.gl/x", Rules.tripStopLink(" https://maps.app.goo.gl/x "))
        assertEquals("geo:25.1,55.2", Rules.tripStopLink("geo:25.1,55.2"))
        assertNull(Rules.tripStopLink("Ali home"))
        assertNull(Rules.tripStopLink(null))
    }

    @Test
    fun tripUndoWindowFromServerTime() {
        val done = Instant.parse("2026-10-21T08:00:00Z")
        assertEquals(5 * 60_000L, Rules.tripUndoLeftMs("2026-10-21T08:00:00Z", done))
        assertEquals(60_000L, Rules.tripUndoLeftMs("2026-10-21T08:00:00+00:00", done.plusSeconds(240)))
        assertEquals(0L, Rules.tripUndoLeftMs("2026-10-21T08:00:00Z", done.plusSeconds(301)))
        assertEquals(0L, Rules.tripUndoLeftMs(null, done))
    }

    // ---- bag label scan (v1.6) ----

    @Test
    fun scanDigitsFromTheLabelAndFromNoise() {
        assertEquals("123605", Rules.scanDigits("123605"))
        assertEquals("123605", Rules.scanDigits(" ORD-123605 "))
        assertEquals("123605", Rules.scanDigits("https://mycrm.center/driver/o/ORD-123605"))
        assertEquals("ORD-123605", Rules.orderNumFromScan("123605"))
        assertNull(Rules.scanDigits(null))
        assertNull(Rules.scanDigits(""))
        assertNull(Rules.scanDigits("hello"))
        assertNull(Rules.scanDigits("123456789"))     // 9 digits — not an order number
        assertNull(Rules.orderNumFromScan("abc"))
    }

    @Test
    fun scanMessages() {
        assertEquals("📦 ORD-1 picked up", Rules.scanMessage("picked_up", "ORD-1", null, null, null, true))
        assertEquals("ORD-1 was already picked up at 10:21 — tap Delivered when it is handed over", Rules.scanMessage("already_picked", "ORD-1", null, null, "10:21", true))
        assertEquals("ORD-1 is assigned to Firoj — not yours. Leave the bag.", Rules.scanMessage("other_driver", "ORD-1", "Firoj", "En Route", null, false))
        assertEquals("ORD-1 is not dispatched yet (Ready for Pick-up) — call the dispatcher", Rules.scanMessage("not_dispatched", "ORD-1", null, "Ready for Pick-up", null, false))
        assertEquals("ORD-1 was already delivered at 14:20 by Kerem", Rules.scanMessage("delivered", "ORD-1", "Kerem", null, "14:20", false))
        assertEquals("ORD-1 was already delivered at 14:20", Rules.scanMessage("delivered", "ORD-1", "Rufat", null, "14:20", true))
        assertEquals("ORD-1 is Cancelled — call the dispatcher", Rules.scanMessage("not_en_route", "ORD-1", null, "Cancelled", null, true))
        assertEquals("No order for this code (ORD-999999)", Rules.scanMessage("not_found", "ORD-999999", null, null, null, false))
        assertEquals("Unknown answer from the server", Rules.scanMessage("???", "ORD-1", null, null, null, false))
    }

    // ---- payment confirmation photo + place line (v1.7) ----

    @Test
    fun paymentPhotoOnlyForLinkOnDelivery() {
        assertTrue(Rules.needsPaymentPhoto("Link on delivery"))
        assertTrue(Rules.needsPaymentPhoto(" LINK  on Delivery "))
        assertFalse(Rules.needsPaymentPhoto("Cash on delivery"))
        assertFalse(Rules.needsPaymentPhoto("Paid via link"))
        assertFalse(Rules.needsPaymentPhoto(null))
    }

    @Test
    fun paymentObjectNameHasThePrefixAndMatchesThePolicy() {
        val at = Instant.parse("2026-10-23T17:03:00Z").toEpochMilli()
        val n = Rules.podObjectName("1F0E9C2A-1234-4ABC-9DEF-00112233AABB", at, 0x00ab, "pay-")
        assertEquals("1f0e9c2a-1234-4abc-9def-00112233aabb/pay-20261023-210300-00ab.jpg", n)
        assertTrue(Rules.POD_OBJECT_NAME.matches(n))
        assertTrue(Rules.POD_OBJECT_NAME.matches(Rules.podObjectName("1f0e9c2a-1234-4abc-9def-00112233aabb", at, 1, "../")))   // junk stripped
    }

    @Test
    fun placeLineEmirateAndDistrict() {
        assertEquals("DUBAI · District One", Rules.placeLine("Dubai", "District One"))
        assertEquals("DUBAI", Rules.placeLine("Dubai", " "))
        assertEquals("JVC", Rules.placeLine(null, "JVC"))
        assertEquals("", Rules.placeLine("", null))
    }
}
