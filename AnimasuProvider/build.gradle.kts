// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Animasu — Streaming Anime Subtitle Indonesia"
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

    iconUrl = "https://1.bp.blogspot.com/-713bWoqV1B8/XcKqwjGjsZI/AAAAAAAAFiM/3nA8P_d50FcCWEnw6uhSeVEtPzKDH6_eQCLcBGAsYHQ/s1600/animasu.ico"
}
