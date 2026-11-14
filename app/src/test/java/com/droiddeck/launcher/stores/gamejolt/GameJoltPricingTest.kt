package com.droiddeck.launcher.stores.gamejolt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class GameJoltPricingTest {
    private fun pricing(amount: Long, promotional: Boolean = false, currency: Any = JSONObject.NULL) =
        JSONObject().put("amount", amount).put("promotional", promotional).put("currency_code", currency)

    private fun sellable(vararg pricings: JSONObject) = JSONObject().put("type", "paid").put("pricings", JSONArray(pricings.toList()))

    @Test fun aRegularPriceIsShownInDollars() {
        val price = GameJoltPricing.of(sellable(pricing(99)))!!
        assertEquals("$0.99", price.final)
        assertEquals("", price.original)
        assertEquals(0, price.discount)
    }

    @Test fun aPromotionalPriceBesideTheRegularOneIsASale() {
        val price = GameJoltPricing.of(sellable(pricing(500), pricing(350, promotional = true)))!!
        assertEquals("$3.50", price.final)
        assertEquals("$5.00", price.original)
        assertEquals(30, price.discount)
    }

    @Test fun aPromotionalPriceAloneShowsNoDiscount() {
        val price = GameJoltPricing.of(sellable(pricing(350, promotional = true)))!!
        assertEquals("$3.50", price.final)
        assertEquals("", price.original)
        assertEquals(0, price.discount)
    }

    @Test fun aPromotionalPriceNotBelowTheRegularOneIsNotASale() {
        val price = GameJoltPricing.of(sellable(pricing(500), pricing(500, promotional = true)))!!
        assertEquals("", price.original)
        assertEquals(0, price.discount)
    }

    @Test fun aGameWithoutPricingsHasNoPrice() {
        assertNull(GameJoltPricing.of(sellable()))
        assertNull(GameJoltPricing.of(null))
    }

    @Test fun anotherCurrencyIsNamed() {
        assertEquals("1.50 EUR", GameJoltPricing.of(sellable(pricing(150, currency = "EUR")))!!.final)
    }
}
