package com.droiddeck.launcher.stores

data class StoreTab(val store: Store, val shown: Boolean)

fun arrangeStoreTabs(order: List<String>, hidden: Set<String>): List<StoreTab> {
    val known = Store.entries.associateBy { it.id }
    val saved = order.mapNotNull { known[it] }.distinct()
    val listed = if (saved.isNotEmpty() && Store.STEAM !in saved) listOf(Store.STEAM) + saved else saved
    return (listed + Store.entries.filterNot { it in listed }).map { StoreTab(it, it.id !in hidden) }
}
