// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Nonton anime subtitle Indonesia dari animeinweb.com"
    authors = listOf("CloudStream")

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

    iconUrl = "https://animeinweb.com/wp-content/uploads/2021/10/cropped-animeinweb-32x32.png"
}
