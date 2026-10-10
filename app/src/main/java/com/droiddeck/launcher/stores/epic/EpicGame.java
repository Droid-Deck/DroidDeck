package com.droiddeck.launcher.stores.epic;

/**
 * One Epic Games library entry. {@code appName} is the launcher's app slug (the manifest URL and
 * the launch arguments take it), {@code namespace} the product's sandbox, {@code catalogItemId}
 * the catalog UUID; the three together address every Epic API call for the game.
 */
public class EpicGame {
    public String appName = "";
    public String namespace = "";
    public String catalogItemId = "";
    public String title = "";
    public String developer = "";
    public String description = "";
    /** DieselGameBoxTall: the tall portrait art. */
    public String artCover = "";
    /** DieselGameBox or Thumbnail. */
    public String artSquare = "";
    public String version = "";
    public long installSize = 0L;
    public boolean canRunOffline = true;
    public boolean isDLC = false;
    public String baseGameCatalogItemId = "";
    public String releaseDate = "";
}
