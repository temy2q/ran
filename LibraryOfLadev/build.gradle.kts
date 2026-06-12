version = 2

cloudstream {
    language = "en"
    description = "Library of Ladev - Neuro-sama stream transcript search with YouTube playback"
    authors = listOf("OpenSourceFlix")

    status = 1
    tvTypes = listOf(
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=libraryofladev.com&sz=%size%"

    isCrossPlatform = true
}

android {
    namespace = "com.ladev"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
