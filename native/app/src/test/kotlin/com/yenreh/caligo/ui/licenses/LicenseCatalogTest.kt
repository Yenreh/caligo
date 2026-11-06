package com.yenreh.caligo.ui.licenses

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What main.dart registers, the libraries' report, and Flutter's paragraphs */
@RunWith(AndroidJUnit4::class)
class LicenseCatalogTest {
    @Test
    fun listsTheAppFirstThenTheFontsAndLibrariesInOrder() = runTest {
        val packages = LicenseCatalog.load(ApplicationProvider.getApplicationContext())
        val names = packages.map { it.name }

        assertEquals("CaliGo", names.first())
        assertEquals(names.drop(1).sortedBy { it.lowercase() }, names.drop(1))
        for (name in listOf("Fraunces", "IBM Plex Sans", "IBM Plex Mono", "com.squareup.okhttp3:okhttp", "androidx.navigation3:navigation3-ui")) {
            assertTrue(name, name in names)
        }
        // BOMs pin versions only: nothing of them ships
        assertTrue(names.none { it.endsWith("-bom") })
    }

    @Test
    fun theAppsGplSaysWhoseProgramItCovers() = runTest {
        val app = LicenseCatalog.load(ApplicationProvider.getApplicationContext()).first()

        val text = app.licenses.single()
        assertTrue(
            text.startsWith(
                "CaliGo\nCopyright (C) 2026 Yenreh\n\nFree software under the GNU General Public License, " +
                    "version 3 or any later version.\n\n",
            ),
        )
        assertTrue("GNU GENERAL PUBLIC LICENSE" in text)
    }

    @Test
    fun everyLibraryCarriesTheFullTextOfItsLicense() = runTest {
        val packages = LicenseCatalog.load(ApplicationProvider.getApplicationContext())

        val okhttp = packages.single { it.name == "com.squareup.okhttp3:okhttp" }
        assertTrue(okhttp.licenses.single().contains("Apache License"))
        assertTrue(packages.all { it.licenses.isNotEmpty() && it.licenses.all(String::isNotBlank) })
    }

    @Test
    fun aLicenseWithoutTextShowsItsNameAndAddress() {
        val packages = LicenseCatalog.libraries(
            """
            {"libraries": [{"uniqueId": "org.example:lib", "licenses": ["X-1", "Unknown"]}],
             "licenses": {"X-1": {"name": "X License", "url": "https://example.org/x"}}}
            """.trimIndent(),
        )

        assertEquals(listOf(PackageLicenses("org.example:lib", listOf("X License\nhttps://example.org/x", "Unknown"))), packages)
    }

    @Test
    fun theAppStaysFirstWhateverItsName() {
        val sorted = LicenseCatalog.sorted(
            listOf(PackageLicenses("b", emptyList()), PackageLicenses("CaliGo", emptyList()), PackageLicenses("A", emptyList())),
        )

        assertEquals(listOf("CaliGo", "A", "b"), sorted.map { it.name })
    }

    /** The BSD example of LicenseEntryWithLineBreaks: six paragraphs, the third to fifth indented */
    @Test
    fun readsParagraphsAsFlutterDoes() {
        val paragraphs = licenseParagraphs(
            """
            Copyright 2016 The Sample Authors. All rights reserved.

            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are
            met:

               * Redistributions of source code must retain the above copyright
            notice, this list of conditions and the following disclaimer.
               * Redistributions in binary form must reproduce the above
            copyright notice, this list of conditions and the following disclaimer
            in the documentation and/or other materials provided with the
            distribution.
               * Neither the name of Example Inc. nor the names of its
            contributors may be used to endorse or promote products derived from
            this software without specific prior written permission.

            THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
            "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES.
            """.trimIndent(),
        )

        assertEquals(listOf(0, 0, 1, 1, 1, 0), paragraphs.map { it.indent })
        assertEquals(
            "Redistribution and use in source and binary forms, with or without modification, " +
                "are permitted provided that the following conditions are met:",
            paragraphs[1].text,
        )
        assertEquals(
            "* Redistributions of source code must retain the above copyright notice, " +
                "this list of conditions and the following disclaimer.",
            paragraphs[2].text,
        )
    }

    @Test
    fun centresAHeadingSetFarIn() {
        val paragraphs = licenseParagraphs("                    GNU GENERAL PUBLIC LICENSE\n                       Version 3\n\nText")

        assertEquals(LicenseParagraph.CENTERED, paragraphs[0].indent)
        assertEquals("GNU GENERAL PUBLIC LICENSE", paragraphs[0].text)
        assertEquals(LicenseParagraph("Version 3", LicenseParagraph.CENTERED), paragraphs[1])
        assertEquals(LicenseParagraph("Text", 0), paragraphs[2])
    }
}
