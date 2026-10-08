package com.droiddeck.launcher.stores.gog;

/** One GOG game as the library cache holds it. */
public class GogGame {
    public final String gameId;
    public final String title;
    public final String imageUrl;
    public final String description;
    public final String developer;
    public final String category;
    /** Content-system generation of the Windows build: 1 or 2 (0 = unknown). */
    public final int generation;
    /** gamesdb.gog.com 2:3 box art, or null when the lookup found none. */
    public final String verticalCover;

    public GogGame(String gameId, String title, String imageUrl, String description, String developer,
                   String category, int generation, String verticalCover) {
        this.gameId = gameId;
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.developer = developer;
        this.category = category;
        this.generation = generation;
        this.verticalCover = verticalCover;
    }
}
