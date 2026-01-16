package ai.opencode.ide.jetbrains.api.models

import com.google.gson.*
import java.lang.reflect.Type

data class FileDiff(
    val file: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int
)

/**
 * 自定义反序列化器，处理服务端返回的 Go 语言 %q 格式字符串。
 * 
 * 服务端在序列化包含非 ASCII 字符的字符串时，可能使用 Go 的 %q 格式，
 * 导致 JSON 中出现类似 "\"\\344\\270\\255\\346\\226\\207.md\"" 的值。
 * 
 * 该反序列化器会：
 * 1. 去掉外层多余的引号
 * 2. 将八进制转义序列 (\xxx) 解码为实际字节
 * 3. 使用 UTF-8 将字节转换回中文字符
 */
class FileDiffDeserializer : JsonDeserializer<FileDiff> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): FileDiff {
        val obj = json.asJsonObject
        
        return FileDiff(
            file = decodeGoQuotedString(obj.get("file")?.asString ?: ""),
            // before 和 after 字段是普通 JSON 字符串，不需要 decodeGoQuotedString 解码
            // 否则会导致：
            // 1. 内容首尾的双引号被错误去除
            // 2. 内容中的反斜杠被错误转义 (e.g. "\\n" -> "\n")
            before = obj.get("before")?.asString ?: "",
            after = obj.get("after")?.asString ?: "",
            additions = obj.get("additions")?.asInt ?: 0,
            deletions = obj.get("deletions")?.asInt ?: 0
        )
    }
    
    companion object {
        /**
         * 解码 Go 语言 %q 格式的字符串。
         * 
         * 例如：
         * - "\"\\344\\270\\255\\346\\226\\207.md\"" -> "中文.md"
         * - "normal.txt" -> "normal.txt" (无变化)
         */
        fun decodeGoQuotedString(input: String): String {
            var s = input
            
            // 如果不包含八进制转义，直接返回
            if (!s.contains("\\")) {
                // 但可能有外层引号
                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                    return s.substring(1, s.length - 1)
                }
                return s
            }
            
            // 去掉外层引号 (Go %q 格式会添加)
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                s = s.substring(1, s.length - 1)
            }
            
            // 处理转义序列
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when {
                        // 八进制转义: \xxx (3位八进制数)
                        i + 3 < s.length && s[i + 1].isDigit() -> {
                            val octal = s.substring(i + 1, i + 4)
                            try {
                                bytes.add(octal.toInt(8).toByte())
                                i += 4
                                continue
                            } catch (_: NumberFormatException) {
                                // 不是有效的八进制，按普通字符处理
                            }
                        }
                        // 常见转义字符
                        s[i + 1] == 'n' -> { bytes.add('\n'.code.toByte()); i += 2; continue }
                        s[i + 1] == 'r' -> { bytes.add('\r'.code.toByte()); i += 2; continue }
                        s[i + 1] == 't' -> { bytes.add('\t'.code.toByte()); i += 2; continue }
                        s[i + 1] == '\\' -> { bytes.add('\\'.code.toByte()); i += 2; continue }
                        s[i + 1] == '"' -> { bytes.add('"'.code.toByte()); i += 2; continue }
                    }
                }
                
                // 普通字符 (ASCII 或 Unicode)
                // 修复: 原来的 s[i].code.toByte() 会导致非 ASCII 字符 (如中文) 被截断从而乱码
                val c = s[i]
                if (c.code < 128) {
                    bytes.add(c.code.toByte())
                } else {
                    val charBytes = c.toString().toByteArray(Charsets.UTF_8)
                    for (b in charBytes) {
                        bytes.add(b)
                    }
                }
                i++
            }
            
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
