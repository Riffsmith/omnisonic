package app.omnisonic.core.model.style

internal const val STYLE_ROOT_PATH: String = "\$"

internal fun stylePath(parent: String, key: String): String =
    if (parent == STYLE_ROOT_PATH) key else "$parent.$key"

internal fun styleIndexPath(parent: String, index: Int): String = "$parent[$index]"

internal class StyleIssues {
    private val errorList = mutableListOf<StyleIssue>()
    private val warningList = mutableListOf<StyleIssue>()

    val errors: List<StyleIssue> get() = errorList
    val warnings: List<StyleIssue> get() = warningList
    val hasErrors: Boolean get() = errorList.isNotEmpty()

    fun error(path: String, detail: String) {
        errorList += StyleIssue(path, detail)
    }

    fun warning(path: String, detail: String) {
        warningList += StyleIssue(path, detail)
    }
}

internal fun styleJsonTypeName(value: Any?): String = when (value) {
    null -> "null"
    is Map<*, *> -> "object"
    is List<*> -> "array"
    is String -> "string"
    is Boolean -> "boolean"
    is Number -> "number"
    else -> "value"
}

internal fun StyleIssues.unknownKeys(
    path: String,
    node: Map<String, Any?>,
    known: Set<String>,
) {
    node.keys
        .filterNot { it in known }
        .forEach { key -> warning(stylePath(path, key), "unknown key dropped") }
}

internal fun StyleIssues.jsonObject(path: String, value: Any?): Map<String, Any?>? {
    if (value is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
    error(path, "expected object, got ${styleJsonTypeName(value)}")
    return null
}

internal fun StyleIssues.jsonArray(path: String, value: Any?): List<Any?>? {
    if (value is List<*>) return value
    error(path, "expected array, got ${styleJsonTypeName(value)}")
    return null
}

internal fun StyleIssues.jsonString(path: String, value: Any?): String? {
    if (value is String) return value
    error(path, "expected string, got ${styleJsonTypeName(value)}")
    return null
}

internal fun StyleIssues.jsonBoolean(path: String, value: Any?): Boolean? {
    if (value is Boolean) return value
    error(path, "expected boolean, got ${styleJsonTypeName(value)}")
    return null
}

internal fun StyleIssues.jsonInt(path: String, value: Any?, range: IntRange? = null): Int? {
    if (value !is Number) {
        error(path, "expected number, got ${styleJsonTypeName(value)}")
        return null
    }
    val number = value.toDouble()
    if (number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
        error(path, "expected an integer, got $value")
        return null
    }
    val integer = number.toInt()
    if (range != null && integer !in range) {
        error(path, "value $integer is out of range ${range.first}..${range.last}")
        return null
    }
    return integer
}

internal fun StyleIssues.jsonFloat(
    path: String,
    value: Any?,
    range: ClosedFloatingPointRange<Float>,
): Float? {
    if (value !is Number) {
        error(path, "expected number, got ${styleJsonTypeName(value)}")
        return null
    }
    val number = value.toFloat()
    if (number !in range) {
        error(path, "value $number is out of range ${range.start}..${range.endInclusive}")
        return null
    }
    return number
}

internal fun <E : Enum<E>> StyleIssues.jsonEnum(
    path: String,
    value: Any?,
    constants: List<E>,
): E? {
    val token = jsonString(path, value) ?: return null
    return constants.firstOrNull { it.name == token } ?: run {
        error(
            path,
            "unknown value '$token'; expected one of ${constants.joinToString { it.name }}",
        )
        null
    }
}

internal fun <T> StyleIssues.optionalField(
    path: String,
    node: Map<String, Any?>,
    key: String,
    fallback: T,
    decode: (String, Any?) -> T?,
): T {
    if (!node.containsKey(key)) return fallback
    return decode(stylePath(path, key), node[key]) ?: fallback
}

internal fun StyleIssues.optionalNullableString(
    path: String,
    node: Map<String, Any?>,
    key: String,
): String? {
    if (!node.containsKey(key) || node[key] == null) return null
    return jsonString(stylePath(path, key), node[key])
}

internal fun <T> StyleIssues.requiredValue(
    path: String,
    node: Map<String, Any?>,
    key: String,
    fallback: T,
    decode: (String, Any?) -> T?,
): T {
    val keyPath = stylePath(path, key)
    if (!node.containsKey(key)) {
        error(keyPath, "required field is missing")
        return fallback
    }
    return decode(keyPath, node[key]) ?: fallback
}
