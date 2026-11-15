package com.droiddeck.launcher.stores.gamejolt

import org.json.JSONObject
import java.util.Locale

internal object GameJoltPricing {
    class Price(val final: String, val original: String, val discount: Int)

    fun of(sellable: JSONObject?): Price? {
        val list = sellable?.optJSONArray("pricings") ?: return null
        val entries = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optLong("amount") > 0L }
        val sale = entries.firstOrNull { it.optBoolean("promotional") }
        val regular = entries.firstOrNull { !it.optBoolean("promotional") }
        val now = sale ?: regular ?: return null
        val was = if (sale != null && regular != null && regular.optLong("amount") > sale.optLong("amount")) regular else null
        val percent = if (was == null) 0 else {
            val before = was.optLong("amount")
            (((before - now.optLong("amount")) * 100 + before / 2) / before).toInt()
        }
        return Price(format(now), was?.let(::format).orEmpty(), percent)
    }

    private fun format(pricing: JSONObject): String {
        val amount = String.format(Locale.US, "%.2f", pricing.optLong("amount") / 100.0)
        val currency = pricing.optString("currency_code").takeIf { it.isNotBlank() && it != "null" && !it.equals("USD", ignoreCase = true) }
        return if (currency == null) "$$amount" else "$amount $currency"
    }
}
