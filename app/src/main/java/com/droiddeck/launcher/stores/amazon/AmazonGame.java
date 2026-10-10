package com.droiddeck.launcher.stores.amazon;

/**
 * One Amazon Games library entry. {@code productId} ("amzn1.adg.product.…") names the game,
 * {@code entitlementId} (the top-level UUID of GetEntitlements) is what GetGameDownload takes,
 * {@code productSku} goes to the game as AMAZON_GAMES_FUEL_PRODUCT_SKU.
 */
public class AmazonGame {
    public String productId = "";
    public String entitlementId = "";
    public String title = "";
    /** iconUrl: square art. */
    public String artUrl = "";
    /** backgroundUrl1: the wide background. */
    public String heroUrl = "";
    public String developer = "";
    public String publisher = "";
    public String productSku = "";
    public String versionId = "";
    public boolean isDLC = false;
    public String parentProductId = "";

    /** The id's last segment, for display when there is no title. */
    public String shortId() {
        int dot = productId.lastIndexOf('.');
        return (dot >= 0 && dot < productId.length() - 1) ? productId.substring(dot + 1) : productId;
    }
}
