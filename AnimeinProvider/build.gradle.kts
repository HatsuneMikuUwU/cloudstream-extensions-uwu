// use an integer for version numbers
version = 1

cloudstream {
    language = "id"
    description = "Animein — Streaming Anime Subtitle Indonesia (API based)"
    authors = listOf("Miku")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // Beta because API may require auth / CF
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=animein.net&sz=%size%"
}
