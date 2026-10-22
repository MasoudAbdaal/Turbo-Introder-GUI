package burp

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.util.regex.Pattern

enum class ExtractionType(val displayName: String) {
    JSON("JSON field"), HEADER("Response header"), REGEX("Regular expression");
    override fun toString(): String = displayName
}

data class ExtractionDefinition(val name: String, val type: ExtractionType, val expression: String)

object ResponseExtractor {
    private val jsonFactory = JsonFactory()

    fun extract(definition: ExtractionDefinition, response: ByteArray?): String {
        if (response == null) return ""
        val raw = String(response, Charsets.ISO_8859_1)
        return try {
            when (definition.type) {
                ExtractionType.JSON -> jsonValue(body(raw), definition.expression)
                ExtractionType.HEADER -> headerValue(raw, definition.expression)
                ExtractionType.REGEX -> regexValue(raw, definition.expression)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun body(response: String): String {
        val marker = response.indexOf("\r\n\r\n")
        return if (marker >= 0) response.substring(marker + 4) else response
    }

    private fun headerValue(response: String, name: String): String {
        val headerEnd = response.indexOf("\r\n\r\n").let { if (it >= 0) it else response.length }
        return response.substring(0, headerEnd).lineSequence().drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator < 1) null
            else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.firstOrNull { it.first.equals(name.trim(), ignoreCase = true) }?.second.orEmpty()
    }

    private fun regexValue(response: String, expression: String): String {
        val match = Pattern.compile(expression, Pattern.DOTALL).matcher(response)
        if (!match.find()) return ""
        return if (match.groupCount() > 0) match.group(1).orEmpty() else match.group().orEmpty()
    }

    private fun jsonValue(json: String, expression: String): String {
        val path = expression.split('.').map(String::trim).filter(String::isNotEmpty)
        if (path.isEmpty()) return ""
        jsonFactory.createParser(json).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return ""
            return findObjectValue(parser, path, 0).orEmpty()
        }
    }

    private fun findObjectValue(parser: JsonParser, path: List<String>, depth: Int): String? {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken != JsonToken.FIELD_NAME) {
                parser.skipChildren()
                continue
            }
            val field = parser.currentName
            val valueToken = parser.nextToken()
            if (field != path[depth]) {
                parser.skipChildren()
                continue
            }
            if (depth == path.lastIndex) {
                return if (valueToken.isScalarValue) parser.valueAsString else {
                    parser.readValueAsTree<com.fasterxml.jackson.core.TreeNode>().toString()
                }
            }
            if (valueToken != JsonToken.START_OBJECT) return null
            return findObjectValue(parser, path, depth + 1)
        }
        return null
    }
}
