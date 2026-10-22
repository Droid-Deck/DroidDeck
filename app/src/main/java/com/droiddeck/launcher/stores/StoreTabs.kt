package com.droiddeck.launcher.stores

data class StoreTab(val store: Store, val shown: Boolean)

fun arrangeStoreTabs(order: List<String>, hidden: Set<String>): List<StoreTab> {
    val known = Store.entries.associateBy { it.id }
    val listed = order.mapNotNull { known[it] }.distinct()
    return (listed + Store.entries.filterNot { it in listed }).map { StoreTab(it, it.id !in hidden) }
}
