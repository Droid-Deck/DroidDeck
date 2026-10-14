package com.droiddeck.launcher.stores.gog;

public class GogGame {
    public final String gameId;
    public final String title;
    public final String imageUrl;
    public final String description;
    public final String developer;
    public final String category;
    public final int generation;
    public final String verticalCover;
    public final String releaseDate;
    public final String publisher;

    public GogGame(String gameId, String title, String imageUrl, String description, String developer,
                   String category, int generation, String verticalCover, String releaseDate, String publisher) {
        this.gameId = gameId;
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.developer = developer;
        this.category = category;
        this.generation = generation;
        this.verticalCover = verticalCover;
        this.releaseDate = releaseDate;
        this.publisher = publisher;
    }
}
