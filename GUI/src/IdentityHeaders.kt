package burp

object IdentityHeaders {
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun validationError(identity: IdentityConfig): String? = runCatching {
        parse(identity.headers)
    }.exceptionOrNull()?.message

    fun apply(request: ByteArray, identity: IdentityConfig?): ByteArray {
        if (identity == null) return request.copyOf()
        val replacements = parse(identity.headers)
        val raw = String(request, Charsets.ISO_8859_1)
        val separator = raw.indexOf("\r\n\r\n")
        if (separator < 0) return request.copyOf()

        val lines = raw.substring(0, separator).split("\r\n")
        if (lines.isEmpty()) return request.copyOf()
        val replacedNames = replacements.map { it.first.lowercase() }.toSet() +
            setOf("cookie", "authorization")
        val headers = lines.drop(1).filterNot { line ->
            val name = line.substringBefore(':', "").trim()
            name.lowercase() in replacedNames
        }.toMutableList()
        replacements.forEach { (name, value) -> headers += "$name: $value" }

        val rebuilt = buildString {
            append(lines.first()).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n").append(raw.substring(separator + 4))
        }
        return rebuilt.toByteArray(Charsets.ISO_8859_1)
    }

    private fun parse(value: String): List<Pair<String, String>> {
        val parsed = value.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid identity header: expected 'Name: value'" }
            val name = line.substring(0, separator).trim()
            val headerValue = line.substring(separator + 1).trim()
            require(headerName.matches(name)) { "Invalid identity header name: $name" }
            require(!headerValue.any { it == '\r' || it == '\n' }) { "Invalid newline in identity header" }
            name to headerValue
        }.toList()
        val duplicate = parsed.groupBy { it.first.lowercase() }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Duplicate identity header: ${duplicate?.value?.first()?.first}" }
        return parsed
    }
}
