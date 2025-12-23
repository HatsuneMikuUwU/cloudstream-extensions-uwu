// use an integer for version numbers
version = 250

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "#1 best extention based on MultiAPI"
     authors = listOf("Hexated", "Sora")

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
        "Anime",
        "Movie",
    )

    iconUrl = "https://cdn.discordapp.com/attachments/1109266606292488297/1193122096159674448/2-modified.png?ex=65ec2a0a&is=65d9b50a&hm=f1e0b0165e71101e5440b47592d9e15727a6c00cdeb3512108067bfbdbef1af7&"
}
