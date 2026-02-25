// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "IndoMax21 — Streaming Anime, Donghua, Hentai, Porn, Movie and TV Series"
    authors = listOf("Miku")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")


    iconUrl = "https://homecookingrocks.com/wp-content/uploads/2024/10/cropped-indomax21-favicon-color-1.png"

}
