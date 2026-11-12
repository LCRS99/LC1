package com.example.rymclient

import android.net.Uri

/**
 * Все адреса сайта собраны здесь: если RYM поменяет структуру ссылок,
 * править нужно только этот файл.
 */
object Config {
    const val HOST = "rateyourmusic.com"
    const val HOME = "https://rateyourmusic.com/"

    /** Раздел нижней навигации: id пункта меню и путь на сайте. */
    class Section(val navId: Int, val path: String)

    val sections = listOf(
        Section(R.id.nav_home, "/"),
        Section(R.id.nav_charts, "/charts/"),
        Section(R.id.nav_new, "/new-music/"),
        Section(R.id.nav_genres, "/genres/"),
        Section(R.id.nav_lists, "/lists/"),
    )

    fun searchUrl(query: String): String =
        "https://$HOST/search?searchterm=${Uri.encode(query)}&searchtype="

    fun sectionUrl(navId: Int): String? =
        sections.firstOrNull { it.navId == navId }?.let { "https://$HOST${it.path}" }

    /** Какой пункт навигации соответствует адресу (null, если ни один). */
    fun navIdFor(url: String?): Int? {
        val path = Uri.parse(url ?: return null).path.orEmpty()
        if (path.isEmpty() || path == "/") return R.id.nav_home
        return sections
            .filter { it.path != "/" }
            .firstOrNull { path.startsWith(it.path.trimEnd('/')) }
            ?.navId
    }
}
