// use an integer for version numbers
version = 4


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "OtakuDesu — Streaming Anime Subtitle Indonesia"
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

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRr-sfek8gXmLpcAppEpaYbUDVtndQbCm_boh1-E91QrmSvsw-2l3-s2JOP&s=10"
}