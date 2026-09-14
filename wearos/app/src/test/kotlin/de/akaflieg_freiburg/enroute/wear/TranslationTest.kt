/***************************************************************************
 *   Copyright (C) 2026 by Soeren Gutbrod                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 3 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/

package de.akaflieg_freiburg.enroute.wear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translations, checked against the source language.
 *
 * A missing translation does not fail a build or throw at runtime: Android quietly falls
 * back to the source language, so a string added in English and never translated shows
 * up in English on a German watch and nobody finds out until a pilot does. That is the
 * whole failure mode this exists to catch.
 *
 * Not every string wants translating. NOTAM, ATIS, ALT, GS, Wi-Fi and the frequencies
 * are the same words in a German cockpit, and translating them would make the display
 * harder to read. Those carry translatable="false" in the source file, which is where
 * Android Lint and a translator's tooling look too, so this test reads the same
 * declaration rather than keeping a list of its own beside it.
 *
 * It kept a list once, and "%1$d of %2$d" was on it -- a string with an English word
 * in the middle. Only Lint noticed, because a list and a file can disagree.
 */
class TranslationTest {

    private val resources = File("src/main/res")

    /**
     * The identifiers the source file declares as the same in every language.
     *
     * Read from translatable="false" rather than listed here: one declaration, seen by
     * this test, by Android Lint and by whoever translates the file next.
     */
    private val sameEverywhere: Set<String>
        get() = UNTRANSLATABLE
            .findAll(File(resources, "values/strings.xml").readText())
            .map { match -> match.groupValues[1] }
            .toSet()

    @Test
    fun `the source language exists and is not empty`() {
        val source = ids(File(resources, "values/strings.xml"))
        assertTrue("no strings in values/strings.xml", source.size > 50)
    }

    @Test
    fun `every translation covers every string that wants translating`() {
        val source = ids(File(resources, "values/strings.xml"))
        val wanted = source - sameEverywhere

        for (translation in translations()) {
            val translated = ids(translation)
            val missing = (wanted - translated).sorted()
            assertEquals(
                translation.parentFile.name + " is missing " + missing.size + " strings: " + missing,
                emptyList<String>(),
                missing,
            )
        }
    }

    @Test
    fun `no translation invents a string the source does not have`() {
        // A renamed or deleted string leaves an orphan behind, which nothing will ever
        // show and which the next translator will waste time on.
        val source = ids(File(resources, "values/strings.xml"))
        for (translation in translations()) {
            val orphans = (ids(translation) - source).sorted()
            assertEquals(
                translation.parentFile.name + " has strings the source does not: " + orphans,
                emptyList<String>(),
                orphans,
            )
        }
    }

    @Test
    fun `a translation keeps the placeholders of its source`() {
        // "%1$d listed" translated without its placeholder throws
        // IllegalFormatException at the moment a pilot opens the page.
        val source = strings(File(resources, "values/strings.xml"))
        for (translation in translations()) {
            for ((id, text) in strings(translation)) {
                val expected = placeholders(source[id] ?: continue)
                assertEquals(
                    translation.parentFile.name + "/" + id + " does not match its source",
                    expected,
                    placeholders(text),
                )
            }
        }
    }

    @Test
    fun `nothing is marked untranslatable and translated at the same time`() {
        // A contradiction rather than a gap: the source says a string is the same in
        // every language and a translation disagrees. One of them is wrong, and a
        // reader has no way to tell which.
        val exempt = sameEverywhere
        assertTrue("no string is marked translatable=false at all", exempt.isNotEmpty())
        for (translation in translations()) {
            val both = (ids(translation) intersect exempt).sorted()
            assertEquals(
                translation.parentFile.name +
                    " translates strings the source marks untranslatable: " + both,
                emptyList<String>(),
                both,
            )
        }
    }

    private fun translations(): List<File> =
        (resources.listFiles() ?: emptyArray())
            .filter { file -> file.isDirectory && file.name.startsWith("values-") }
            .map { directory -> File(directory, "strings.xml") }
            .filter { file -> file.isFile }

    private fun strings(file: File): Map<String, String> {
        val text = file.readText()
        return NAMED.findAll(text).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun ids(file: File): Set<String> = strings(file).keys

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { match -> match.value }.toList()

    private companion object {
        val UNTRANSLATABLE =
            Regex("""<string name="([^"]+)"[^>]*translatable="false"""")
        val NAMED = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
