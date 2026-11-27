package com.droiddeck.launcher.stores

data class StoreTab(val store: Store, val shown: Boolean)

fun arrangeStoreTabs(order: List<String>, hidden: Set<String>): List<StoreTab> {
    val known = Store.tabbed.associateBy { it.id }
    val canonicalOrder = order.map { Store.byId(it)?.id ?: it }
    val canonicalHidden = hidden.mapTo(HashSet()) { Store.byId(it)?.id ?: it }
    val listed = canonicalOrder.mapNotNull { known[it] }.distinct()
    return (listed + Store.tabbed.filterNot { it in listed }).map { StoreTab(it, it.id !in canonicalHidden) }
}
