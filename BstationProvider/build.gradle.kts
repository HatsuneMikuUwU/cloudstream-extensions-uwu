// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them
    description = "Bstation - International streaming platform for anime, movies, and variety shows (bilibili.tv)"
    authors = listOf("Miku")
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // Beta only
    tvTypes = listOf(
        "Anime",
        "Movies",
        "TvSeries",
        "Documentary",
    )

    iconUrl = "https://play-lh.googleusercontent.com/G9s84Cm1TDnKDX2P8nipS_s60cuCnYtjBRRLespF8nivjXmbV9tF1fY37clZhXMLaA"

}
