package dev.cartridge

import dev.cartridge.scraping.*
import org.junit.Assert.*
import org.junit.Test

class ScrapePreferencesTest {
    @Test fun preferencesRankTaggedAlternativesWithoutDroppingChoicesOrInventingLanguages() {
        val us = ArtworkCandidate("support-texture", "us", "https://example.invalid/us")
        val jp = us.copy(region = "jp", url = "https://example.invalid/jp")
        val secondJp = jp.copy(url = "https://example.invalid/jp2")
        val box = us.copy(type = "box-2D", region = "jp", url = "https://example.invalid/box")
        val source = GameCandidate("one", "Original", "Game Boy Advance", emptyList(), linkedMapOf(
            "title" to listOf("US title", "JP title", "Unknown"), "description" to listOf("English", "French")),
            listOf(box, us, jp, secondJp), "", "Original fixture", metadataTags = mapOf(
                "title" to listOf(MetadataTags(region = "us"), MetadataTags(region = "jp")),
                "description" to listOf(MetadataTags(language = "en"), MetadataTags(language = "fr"))))
        val ranked = ScrapePreferences("jp", "fr").rank(source)
        assertEquals(listOf(jp, secondJp, us, box), ranked.artwork)
        assertEquals(listOf("JP title", "Unknown", "US title"), ranked.metadata["title"])
        assertEquals(listOf("French", "English"), ranked.metadata["description"])
        assertEquals("fr", ranked.metadataTags["description"]!![0].language)
        assertEquals("jp", ranked.metadataTags["title"]!![0].region)
        assertEquals("", ranked.metadataTags["title"]!![0].language)
        assertEquals(source.metadata, ScrapePreferences("any", "any").rank(source).metadata)
        assertEquals(4, ranked.artwork.size)
        assertEquals(listOf(box, us, jp, secondJp), source.artwork)
    }
    @Test fun legacyUntaggedMetadataKeepsItsOriginalOrder() {
        val source = GameCandidate("one", "Original", "Game Boy Advance", emptyList(),
            mapOf("title" to listOf("One", "Two")), emptyList(), "", "Original fixture")
        assertEquals(source.metadata, ScrapePreferences("jp", "ja").rank(source).metadata)
    }
}
