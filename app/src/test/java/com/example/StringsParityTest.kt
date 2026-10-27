package com.example

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class StringsParityTest {

    private fun keys(path: String): Set<String> {
        val file = sequenceOf(
            File(path),
            File("app/$path"),
            File("../$path")
        ).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    @Test
    fun idAndEnHaveIdenticalStringKeys() {
        val id = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        val missingInEn = id - en
        val missingInId = en - id
        assertEquals("Key hilang di values-en: $missingInEn", emptySet<String>(), missingInEn)
        assertEquals("Key hilang di values (id): $missingInId", emptySet<String>(), missingInId)
    }
}
