// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "CineMax21 — Streaming Movie and TV Series"
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
        "AsianDrama",
        "TvSeries",
        "Movie",
    )


    iconUrl = "https://i0.wp.com/blokmsquare.co.id/wp-content/uploads/2020/01/Cinema-21-Blok-M-Square.png?fit=626%2C626&ssl=1"

}
