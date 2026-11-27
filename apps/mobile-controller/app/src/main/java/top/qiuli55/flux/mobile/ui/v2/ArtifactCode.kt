package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 代码块语言标签与轻量高亮。
 *
 * 只用正则识别「注释 / 字符串 / 数字 / 关键字」，不追求完整语法分析，也不引入新依赖。
 * 识别结果按区间排序后依次写入 [AnnotatedString]，重叠部分先到先得。
 */
object ArtifactCode {

    private val KEYWORDS = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "data", "when", "if", "else",
            "for", "while", "return", "import", "package", "private", "public", "internal",
            "suspend", "override", "null", "true", "false",
        ),
        "java" to setOf(
            "class", "interface", "enum", "public", "private", "protected", "static", "final",
            "void", "int", "long", "double", "boolean", "if", "else", "for", "while", "return",
            "new", "null", "true", "false", "import", "package",
        ),
        "js" to setOf(
            "const", "let", "var", "function", "=>", "class", "import", "export", "from",
            "return", "if", "else", "for", "while", "new", "null", "true", "false", "async",
            "await", "try", "catch", "throw",
        ),
        "python" to setOf(
            "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
            "in", "not", "and", "or", "None", "True", "False", "try", "except", "raise",
            "with", "as", "lambda", "yield", "async", "await",
        ),
        "go" to setOf(
            "func", "package", "import", "return", "if", "else", "for", "range", "type",
            "struct", "interface", "var", "const", "go", "defer", "nil", "true", "false",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "struct", "enum", "impl", "trait", "use", "pub", "return",
            "if", "else", "for", "while", "match", "self", "None", "Some", "true", "false",
        ),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "join", "left", "right", "on", "group", "by", "order", "limit",
        ),
    )

    private val languageAliases = mapOf(
        "js" to "js", "javascript" to "js", "jsx" to "js",
        "ts" to "js", "typescript" to "js", "tsx" to "js",
        "kt" to "kotlin", "kts" to "kotlin", "kotlin" to "kotlin",
        "java" to "java",
        "py" to "python", "python" to "python",
        "go" to "go", "golang" to "go",
        "rs" to "rust", "rust" to "rust",
        "sh" to "shell", "bash" to "shell", "shell" to "shell", "zsh" to "shell",
        "sql" to "sql",
        "json" to "json", "yaml" to "json", "yml" to "json", "toml" to "json",
        "html" to "html", "xml" to "html", "css" to "css",
    )

    private val labelAliases = mapOf(
        "javascript" to "JS", "js" to "JS", "jsx" to "JSX",
        "typescript" to "TS", "ts" to "TS", "tsx" to "TSX",
        "kotlin" to "KOTLIN", "kt" to "KOTLIN", "kts" to "KOTLIN",
        "python" to "PY", "py" to "PY",
        "shell" to "SH", "bash" to "SH", "sh" to "SH", "zsh" to "SH",
        "golang" to "GO", "go" to "GO",
        "rust" to "RUST", "rs" to "RUST",
    )

    /** 代码块右上角的语言标签；未知语言退化为大写短名，空语言返回空串。 */
    fun label(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return labelAliases[value.lowercase()] ?: value.uppercase().take(12)
    }

    fun normalize(raw: String?): String =
        languageAliases[raw?.trim()?.lowercase().orEmpty()] ?: "plain"

    private data class Token(val start: Int, val end: Int, val color: Color)

    private val COMMENT = Color(0xFF6B7280)
    private val STRING = Color(0xFF15803D)
    private val NUMBER = Color(0xFFB45309)
    private val KEYWORD = Color(0xFF7C3AED)

    /**
     * 轻量高亮：注释 → 字符串 → 数字 → 关键字；区间不重叠，先识别到的优先。
     * 纯函数，单测直接比较 [AnnotatedString.text] 与 span 数量。
     */
    fun highlight(code: String, language: String?): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        val lang = normalize(language)
        val tokens = mutableListOf<Token>()
        val taken = BooleanArray(code.length)

        fun addAll(regex: Regex, color: Color, group: Int = 0) {
            for (match in regex.findAll(code)) {
                val range = if (group == 0) match.range else match.groups[group]?.range ?: continue
                if (range.first < 0 || range.last >= code.length) continue
                if ((range.first..range.last).any { taken[it] }) continue
                for (i in range) taken[i] = true
                tokens += Token(range.first, range.last + 1, color)
            }
        }

        // JSON 里的 `#` 只可能出现在字符串（如颜色值），不能当行注释，否则会吃掉整行。
        val hashComments = lang in setOf("python", "shell")
        val commentPattern = if (hashComments) {
            Regex("(?m)#[^\\n]*|/\\*[\\s\\S]*?\\*/")
        } else {
            Regex("(?m)//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        }
        addAll(commentPattern, COMMENT)
        addAll(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`"), STRING)
        addAll(Regex("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER)
        val words = KEYWORDS[lang]
        if (words != null && words.isNotEmpty()) {
            val pattern = Regex("\\b(?:${words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\\b")
            addAll(pattern, KEYWORD)
        }

        val ordered = tokens.sortedBy { it.start }
        return buildAnnotatedString {
            var cursor = 0
            ordered.forEach { token ->
                if (token.start > cursor) append(code.substring(cursor, token.start))
                withStyle(SpanStyle(color = token.color)) {
                    append(code.substring(token.start, token.end))
                }
                cursor = token.end
            }
            if (cursor < code.length) append(code.substring(cursor))
        }
    }
}
