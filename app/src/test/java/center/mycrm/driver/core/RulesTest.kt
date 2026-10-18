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
}
