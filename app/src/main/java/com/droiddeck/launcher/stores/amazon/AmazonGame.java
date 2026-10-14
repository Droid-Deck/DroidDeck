package com.droiddeck.launcher.stores.amazon;

public class AmazonGame {
    public String productId = "";
    public String entitlementId = "";
    public String title = "";
    public String artUrl = "";
    public String heroUrl = "";
    public String developer = "";
    public String publisher = "";
    public String productSku = "";
    public String versionId = "";
    public boolean isDLC = false;
    public String parentProductId = "";

    public String shortId() {
        int dot = productId.lastIndexOf('.');
        return (dot >= 0 && dot < productId.length() - 1) ? productId.substring(dot + 1) : productId;
    }
}
