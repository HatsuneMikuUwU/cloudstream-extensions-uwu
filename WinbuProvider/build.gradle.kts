// use an integer for version numbers
version = 3

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Winbu — Streaming Anime & Series with Indonesian Subtitles"
    authors = listOf("Miku")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
        "TvSeries",
    )

    iconUrl = "https://winbu.org/wp-content/uploads/2025/04/file-0000000068a051f6aed60f781c75581b-conversation-id-67ef899f-e0f8-8011-8643-02848517c045-message-i-1-e1744432620825.png"
}
