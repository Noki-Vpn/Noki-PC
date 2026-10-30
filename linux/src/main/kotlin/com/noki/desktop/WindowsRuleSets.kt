package com.noki.desktop

import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.vpn.GeositeAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** Resolve named local files once on Save; connection startup reads checked snapshots. */
internal object WindowsRuleSets {
    const val MAX_BYTES = 32L * 1024 * 1024
    private val selectorName = Regex("[a-z0-9_@-]{1,120}")

    fun normalizeGeoIp(value: String): String {
        val name = value.substringAfter(':').trim().lowercase(java.util.Locale.ROOT)
        require(value.substringBefore(':').trim().equals("geoip", true) && selectorName.matches(name)) { "Некорректное имя geoip: $value" }
        return "geoip:$name"
    }

    suspend fun resolve(vault: WindowsVault, runtime: SingBoxRuntime, rule: DesktopRoutingRule): DesktopRoutingRule = withContext(Dispatchers.IO) {
        val row = rule.normalized()
        if (!row.enabled) return@withContext row.copy(ruleSets = row.ruleSets.filter { it.name in row.domains || it.name in row.ips })
        val sources = mutableListOf<DesktopRuleSet>()
        for (selector in (row.domains.filter { it.startsWith("geosite:") } + row.ips.filter { it.startsWith("geoip:") }).distinct()) {
            val kind = selector.substringBefore(':')
            val name = selector.substringAfter(':')
            require(selectorName.matches(name)) { "Некорректное имя списка: $selector" }
            val directory = Files.createDirectories(vault.directory.resolve(kind))
            val candidates = listOf("srs", "json", "dat", "txt").map { directory.resolve("$name.$it") }
            val path = candidates.firstOrNull(Files::isRegularFile)
                ?: directory.resolve("$kind.dat").takeIf { selector != DomainRulePolicy.RUSSIAN_RESOURCES_RULE && Files.isRegularFile(it) }
            if (path == null) {
                require(kind == "geosite" && row.ruleSets.none { it.name == selector }) { "Файл не найден: ${directory.resolve("$name.srs")}" }
                continue // Server-supplied geosite categories remain available.
            }
            require(sources.size < 10) { "В одном правиле можно добавить до 10 списков" }
            sources += importFile(vault, runtime, path, name, selector)
        }
        row.copy(ruleSets = sources).normalized()
    }

    private suspend fun importFile(vault: WindowsVault, runtime: SingBoxRuntime, source: Path, category: String, selector: String): DesktopRuleSet = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(source) && Files.size(source) in 1..MAX_BYTES) { "Нужен файл списка размером до 32 МБ: $source" }
        val name = source.fileName.toString()
        val bytes = Files.newInputStream(source).use { it.readNBytes(MAX_BYTES.toInt() + 1) }
        require(bytes.size.toLong() in 1..MAX_BYTES) { "Пустой список или размер больше 32 МБ" }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val input = Files.createTempFile(vault.directory, "ruleset-import-", ".input")
        val decoded = Files.createTempFile(vault.directory, "ruleset-import-", ".json")
        val compiled = Files.createTempFile(vault.directory, "ruleset-import-", ".srs")
        try {
            Files.write(input, bytes)
            val document = when {
                bytes.take(3).toByteArray().contentEquals(byteArrayOf(83, 82, 83)) -> {
                    runtime.ruleSetCommand("decompile", input, decoded)
                    JSONObject(Files.readString(decoded))
                }
                name.endsWith(".dat", true) ->
                    JSONObject().put("version", 3).put("rules", JSONArray().put(GeositeAsset.resolveCustom(bytes, category)))
                else -> {
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().trimStart('\uFEFF')
                    if (text.trimStart().startsWith("{")) JSONObject(text) else textList(text)
                }
            }
            val dns = checkedSource(document)
            val kind = selector.substringBefore(':')
            require(kind in setOf("geosite", "geoip"))
            fun checkKind(rule: JSONObject) {
                if (rule.optString("type") == "logical") {
                    val children = rule.getJSONArray("rules")
                    for (index in 0 until children.length()) checkKind(children.getJSONObject(index))
                } else require(rule.keySet().filter { it != "invert" }.all { if (kind == "geoip") it == "ip_cidr" else it != "ip_cidr" }) {
                    "Файл $selector содержит условия другого типа"
                }
            }
            val rules = document.getJSONArray("rules")
            for (index in 0 until rules.length()) checkKind(rules.getJSONObject(index))
            val canonical = document.toString()
            val encoded = canonical.toByteArray(Charsets.UTF_8)
            require(encoded.size <= MAX_BYTES) { "Распакованный список больше 32 МБ" }
            Files.write(decoded, encoded)
            runtime.ruleSetCommand("compile", decoded, compiled)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val digest = GeositeAsset.sha256(encoded)
            val path = vault.writePrivate("ruleset-$digest.json", canonical).toAbsolutePath().normalize()
            val dnsPath = if (dns.length() == 0) null else vault.writePrivate("ruleset-$digest.dns.json",
                JSONObject().put("version", document.getInt("version")).put("rules", dns).toString()).toAbsolutePath().normalize().toString()
            DesktopRuleSet(path.toString(), selector, dnsPath).normalized()
        } finally {
            Files.deleteIfExists(input); Files.deleteIfExists(decoded); Files.deleteIfExists(compiled)
        }
    }

    internal fun textList(text: String): JSONObject {
        val domains = linkedMapOf<String, MutableList<String>>()
        val ips = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isBlank() || line.startsWith('#') || line.startsWith("//")) continue
            val ip = runCatching { SingBoxConfig.normalizeIpRule(line) }.getOrNull()
            if (ip != null) { ips += ip; continue }
            val domain = requireNotNull(DomainRulePolicy.normalize(line)) { "Некорректная строка: ${line.take(120)}" }
            val key = when (domain.substringBefore(':')) { "domain" -> "domain_suffix"; "full" -> "domain"; "regexp" -> "domain_regex"; else -> error("Для geosite: используйте поле доменов или файл .dat") }
            domains.getOrPut(key) { mutableListOf() }.add(domain.substringAfter(':'))
        }
        val rules = JSONArray()
        if (domains.isNotEmpty()) rules.put(JSONObject().also { rule -> domains.forEach { (key, values) -> rule.put(key, JSONArray(values.distinct())) } })
        if (ips.isNotEmpty()) rules.put(JSONObject().put("ip_cidr", JSONArray(ips.distinct())))
        return JSONObject().put("version", 3).put("rules", rules)
    }

    /** Project only domain conditions to DNS; IP-only lists must not expose DNS. */
    internal fun checkedSource(document: JSONObject): JSONArray {
        require(document.keySet().all { it in setOf("version", "rules") } && document.getInt("version") in 1..5) { "Нужен список sing-box, а не полная конфигурация" }
        fun check(rule: JSONObject, depth: Int): JSONObject? {
            require(depth <= 32) { "Слишком глубокая структура списка" }
            if (rule.optString("type") == "logical") {
                require(rule.keySet().all { it in setOf("type", "mode", "rules", "invert") } && rule.getString("mode") in setOf("and", "or")) { "Неподдерживаемое условие списка" }
                val children = rule.getJSONArray("rules")
                require(children.length() in 1..100_000)
                val dns = (0 until children.length()).mapNotNull { check(children.getJSONObject(it), depth + 1) }
                if (dns.isEmpty() || (dns.size != children.length() && (rule.getString("mode") == "and" || rule.optBoolean("invert")))) return null
                return JSONObject(rule.toString()).put("rules", JSONArray(dns))
            }
            require(rule.keySet().all { it in setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr", "invert") }) { "Список должен содержать домены или IP, без настроек протокола и приложений" }
            require(rule.keySet().any { it != "invert" }) { "Пустое условие списка" }
            for (key in rule.keySet().filter { it != "invert" }) {
                val items = if (rule.get(key) is String) listOf(rule.getString(key)) else rule.getJSONArray(key).let { values ->
                    require(values.length() in 1..1_000_000)
                    (0 until values.length()).map(values::getString)
                }
                require(items.isNotEmpty() && items.all { it.isNotBlank() && it.length <= 2048 }) { "Некорректная запись списка" }
                if (key == "ip_cidr") items.forEach(SingBoxConfig::normalizeIpRule)
                if (key == "domain_regex") items.forEach { com.google.re2j.Pattern.compile(it) }
            }
            if (rule.has("invert")) rule.getBoolean("invert")
            return if (rule.has("ip_cidr")) null else JSONObject(rule.toString())
        }
        val rules = document.getJSONArray("rules")
        require(rules.length() in 1..100_000) { "Список пуст или слишком велик" }
        return JSONArray((0 until rules.length()).mapNotNull { check(rules.getJSONObject(it), 0) })
    }
}
