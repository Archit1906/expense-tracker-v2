package com.example.espensestracker.sms

import com.example.espensestracker.data.*
import org.junit.Assert.*
import org.junit.Test

class SmsRegressionTest {
    private fun p(body: String, sender: String = "VM-HDFCBK") = SmsParser.parse(sender, body, 1_700_000_000_000)
    @Test fun failedAndPendingAreNotExpenses() {
        assertEquals(ParseResult.Ignore, p("UPI payment of Rs. 500 to swiggy@upi failed for A/c XX1234. Amount not debited."))
        assertEquals(ParseResult.Ignore, p("Rs.500 debited from A/c XX1234; UPI payment pending."))
    }
    @Test fun reversalTakesPrecedenceOverDebit() {
        val r = p("Rs. 500 debited earlier from A/c XX1234 has been reversed and credited back.") as ParseResult.Ok
        assertEquals(TYPE_REFUND, r.tx.type)
        assertEquals(500.0, r.tx.amount, 0.0)
    }
    @Test fun instamartIsGroceries() {
        val r = p("Rs.350 debited from A/c XX1234 to swiggyinstamart@upi on 25-Nov.") as ParseResult.Ok
        assertEquals("Groceries", r.tx.category)
    }
    @Test fun unknownSenderRequiresExplicitReview() {
        val body = "Rs. 5000 credited to your bank A/c XX1234. Avl Bal Rs. 90000."
        val r = p(body, "XX-FAKEID") as ParseResult.Review
        assertTrue(r.sms.explicitReview)
        assertEquals(TYPE_CREDIT, r.sms.type)
        assertNull(SmsParser.balanceOf("XX-FAKEID", body, 1L))
    }
    @Test fun signedBalanceIsAccepted() {
        assertEquals(-250.0, SmsParser.balanceOf("VM-HDFCBK", "A/c XX1234 Avl Bal Rs. -250.00", 1L)!!.amount, 0.0)
    }
    @Test fun malformedAmountsAreRejected() {
        assertEquals(ParseResult.Ignore, p("Rs.100.999 debited from A/c XX1234 to swiggy@upi"))
        assertEquals(ParseResult.Ignore, p("Rs.1,2,3 debited from A/c XX1234 to swiggy@upi"))
        assertEquals(ParseResult.Ignore, p("Rs.0 debited from A/c XX1234 to swiggy@upi"))
    }
    @Test fun fullVpaIsUsedForIdentity() {
        val a = p("Rs.50 debited from A/c XX1234 to shop@upi") as ParseResult.Review
        val b = p("Rs.50 debited from A/c XX1234 to shop@okaxis") as ParseResult.Review
        assertTrue(a.sms.merchantKey != b.sms.merchantKey)
    }
    @Test fun reminderDoesNotBecomePayment() {
        assertEquals(ParseResult.Ignore, p("Your credit card payment due is Rs. 5000. Pay before 25-Nov."))
    }
}
