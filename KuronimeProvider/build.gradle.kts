// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Kuronime — Anime Sub Indo"
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
    )

    iconUrl = "https://play-lh.googleusercontent.com/EFMkS1T-8txjLFIl9QxjcTRLa7a7Muv08C04b7ongYgodoY_b2T3GpxzGAQEx-eitfQ=w256"
}