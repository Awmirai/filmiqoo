package com.filmiqoo.app

data class PersonCredit(
    val media: MediaItem,
    val role: String,
    val department: String
)

data class PersonDetail(
    val id: Int,
    val name: String,
    val biography: String,
    val birthday: String,
    val deathday: String?,
    val placeOfBirth: String,
    val knownForDepartment: String,
    val profilePath: String?,
    val alsoKnownAs: List<String>,
    val imdbId: String?,
    val images: List<String>,
    val credits: List<PersonCredit>
) {
    val ageOrYears: String
        get() {
            val born=birthday.take(4).toIntOrNull() ?: return ""
            val died=deathday?.take(4)?.toIntOrNull()
            val end=died ?: java.time.LocalDate.now().year
            val years=(end-born).coerceAtLeast(0)
            return years.toString()
        }

    val movieCredits: List<PersonCredit>
        get() = credits.filter { it.media.type==MediaType.MOVIE }

    val tvCredits: List<PersonCredit>
        get() = credits.filter { it.media.type==MediaType.TV }
}
