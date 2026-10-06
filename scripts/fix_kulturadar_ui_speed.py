from pathlib import Path
import re


def read(path: str) -> tuple[Path, str]:
    p = Path(path)
    return p, p.read_text()


def write(p: Path, s: str):
    p.write_text(s)


def replace_once(s: str, old: str, new: str, label: str) -> str:
    if old not in s:
        raise SystemExit(f"{label}: target not found")
    return s.replace(old, new, 1)


def regex_once(s: str, pattern: str, repl: str, label: str) -> str:
    out, n = re.subn(pattern, repl, s, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {n}")
    return out


# --- UI: collapsible filters and visible fallback for failed images ---
ui_path, ui = read("app/src/main/java/cz/kulturadar/app/ui/KulturadarProApp.kt")
if "import coil.compose.SubcomposeAsyncImage" not in ui:
    ui = replace_once(
        ui,
        "import coil.compose.AsyncImage\n",
        "import coil.compose.AsyncImage\nimport coil.compose.SubcomposeAsyncImage\n",
        "SubcomposeAsyncImage import",
    )

controls = r'''@Composable
private fun ProControls(whenValue: ProWhen, onWhen: (ProWhen) -> Unit, type: EventType, onType: (EventType) -> Unit, sort: ProSort, onSort: (ProSort) -> Unit, freeOnly: Boolean, onFree: (Boolean) -> Unit, imageOnly: Boolean, onImage: (Boolean) -> Unit, maxPrice: Int\?, onPrice: \(Int\?\) -> Unit, premium: Boolean\) \{.*?\n\}\n\n@Composable\nprivate fun ProMessage'''
controls_repl = '''@Composable
private fun ProControls(whenValue: ProWhen, onWhen: (ProWhen) -> Unit, type: EventType, onType: (EventType) -> Unit, sort: ProSort, onSort: (ProSort) -> Unit, freeOnly: Boolean, onFree: (Boolean) -> Unit, imageOnly: Boolean, onImage: (Boolean) -> Unit, maxPrice: Int?, onPrice: (Int?) -> Unit, premium: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Skrýt filtry" else "Filtry")
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${whenValue.label} · ${type.title}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ProWhen.entries.forEach { item -> FilterChip(selected = whenValue == item, onClick = { onWhen(item) }, label = { Text(item.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                EventType.entries.forEach { item -> FilterChip(selected = type == item, onClick = { onType(item) }, label = { Text(item.title) }) }
            }
            if (premium) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ProSort.entries.forEach { item -> FilterChip(selected = sort == item, onClick = { onSort(item) }, label = { Text(item.label) }) }
                    FilterChip(selected = freeOnly, onClick = { onFree(!freeOnly) }, label = { Text("Zdarma") }, leadingIcon = { Icon(Icons.Default.MoneyOff, null) })
                    FilterChip(selected = imageOnly, onClick = { onImage(!imageOnly) }, label = { Text("S fotkou") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                    FilterChip(selected = maxPrice != null, onClick = { onPrice(if (maxPrice == null) 1000 else null) }, label = { Text(maxPrice?.let { "Do $it Kč" } ?: "Cena") }, leadingIcon = { Icon(Icons.Default.Payments, null) })
                }
            }
        }
    }
}

@Composable
private fun ProMessage'''
ui = regex_once(ui, controls, controls_repl, "collapsible filters")

image_pattern = r'''@Composable
private fun ProImage\(e: CulturalEvent, modifier: Modifier = Modifier\) \{.*?\n\}\n\n@Composable\nprivate fun ProDetail'''
image_repl = '''@Composable
private fun ProImage(e: CulturalEvent, modifier: Modifier = Modifier) {
    val url = e.imageUrl?.trim()?.takeIf { it.startsWith("https://", true) }
    if (url != null) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = e.title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = { ProImageFallback(e, Modifier.fillMaxSize(), loading = true) },
            error = { ProImageFallback(e, Modifier.fillMaxSize(), loading = false) }
        )
    } else {
        ProImageFallback(e, modifier, loading = false)
    }
}

@Composable
private fun ProImageFallback(e: CulturalEvent, modifier: Modifier, loading: Boolean) {
    Box(
        modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color(0xFF102218), Color(0xFF050706)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    when (e.type) {
                        EventType.CONCERT -> Icons.Default.MusicNote
                        EventType.CINEMA -> Icons.Default.Movie
                        EventType.THEATRE -> Icons.Default.TheaterComedy
                        EventType.EXHIBITION -> Icons.Default.Museum
                        else -> Icons.Default.Event
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(if (loading) "Načítám obrázek…" else "Obrázek není dostupný", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProDetail'''
ui = regex_once(ui, image_pattern, image_repl, "image fallback")
write(ui_path, ui)


# --- EventRepository: fetch sources concurrently and cap total wait ---
event_path, event = read("app/src/main/java/cz/kulturadar/app/data/EventRepository.kt")
if "import java.util.concurrent.Executors" not in event:
    event = replace_once(
        event,
        "import java.nio.charset.StandardCharsets\n",
        "import java.nio.charset.StandardCharsets\nimport java.util.concurrent.Callable\nimport java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n",
        "EventRepository imports",
    )
old = '''        val goOutResult = goOut.load(null, keyword)
        val publicResult = publicSources.load(null, keyword)
        val smsResult = smsTicket.load(city)
        val ticketResult = if (apiKey.isNotBlank()) loadTicketmaster(apiKey, city, keyword) else EventLoadResult(emptyList())'''
new = '''        val pool = Executors.newFixedThreadPool(4)
        val goFuture = pool.submit(Callable { goOut.load(city, keyword) })
        val publicFuture = pool.submit(Callable { publicSources.load(city, keyword) })
        val smsFuture = pool.submit(Callable { smsTicket.load(city) })
        val ticketFuture = pool.submit(Callable { if (apiKey.isNotBlank()) loadTicketmaster(apiKey, city, keyword) else EventLoadResult(emptyList()) })
        pool.shutdown()
        runCatching { pool.awaitTermination(7, TimeUnit.SECONDS) }
        if (!pool.isTerminated) pool.shutdownNow()

        val goOutResult = if (goFuture.isDone && !goFuture.isCancelled) runCatching { goFuture.get() }.getOrElse { EventLoadResult(emptyList(), "GoOut se nepodařilo načíst.") } else EventLoadResult(emptyList(), "GoOut neodpověděl včas.")
        val publicResult = if (publicFuture.isDone && !publicFuture.isCancelled) runCatching { publicFuture.get() }.getOrElse { EventLoadResult(emptyList(), "Veřejné kalendáře se nepodařilo načíst.") } else EventLoadResult(emptyList(), "Veřejné kalendáře neodpověděly včas.")
        val smsResult = if (smsFuture.isDone && !smsFuture.isCancelled) runCatching { smsFuture.get() }.getOrElse { EventLoadResult(emptyList(), "SMSticket se nepodařilo načíst.") } else EventLoadResult(emptyList(), "SMSticket neodpověděl včas.")
        val ticketResult = if (ticketFuture.isDone && !ticketFuture.isCancelled) runCatching { ticketFuture.get() }.getOrElse { EventLoadResult(emptyList(), "Ticketmaster se nepodařilo načíst.") } else EventLoadResult(emptyList(), "Ticketmaster neodpověděl včas.")'''
event = replace_once(event, old, new, "parallel source loading")
write(event_path, event)


# --- Public sources: load only relevant local calendars + national sources, fewer detail requests ---
pub_path, pub = read("app/src/main/java/cz/kulturadar/app/data/PublicEventSourcesRepository.kt")
pub = replace_once(
    pub,
    '''        val pool = Executors.newFixedThreadPool(7)
        return try {
            val futures = sources.map { source -> Callable { loadSource(source) } }.map(pool::submit)''',
    '''        val requestedCity = city?.takeIf { it.isNotBlank() && it != "Všechna města" }
        val activeSources = if (requestedCity == null) sources else sources.filter { source ->
            source.fallbackCity == null || source.fallbackCity.equals(requestedCity, true)
        }
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val futures = activeSources.map { source -> Callable { loadSource(source) } }.map(pool::submit)''',
    "public active sources",
)
pub = replace_once(
    pub,
    '            val requestedCity = city?.takeIf { it.isNotBlank() && it != "Všechna města" }\n            val q = keyword.orEmpty().trim()',
    '            val q = keyword.orEmpty().trim()',
    "public duplicate requestedCity",
)
pub = pub.replace("future.get(15, TimeUnit.SECONDS)", "future.get(6, TimeUnit.SECONDS)")
pub = pub.replace(".take(30)\n            .toList()", ".take(14)\n            .toList()", 1)
pub = pub.replace("f.get(8, TimeUnit.SECONDS)", "f.get(4, TimeUnit.SECONDS)")
old_img = '''        val image = imageUrl(json.opt("image"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)'''
new_img = '''        val image = resolveImage(pageUrl, imageUrl(json.opt("image")))
            ?: resolveImage(pageUrl, doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: resolveImage(pageUrl, doc.selectFirst("meta[name=twitter:image]")?.attr("content"))
            ?: resolveImage(pageUrl, doc.selectFirst("link[rel=image_src]")?.attr("href"))
            ?: resolveImage(pageUrl, doc.selectFirst("img[data-src], img[data-lazy-src], img[src]")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            })'''
pub = replace_once(pub, old_img, new_img, "public image extraction")
helper_anchor = '''    private fun imageUrl(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
        is JSONObject -> value.optString("url").takeIf(String::isNotBlank)
        else -> null
    }'''
helper_new = helper_anchor + '''

    private fun resolveImage(baseUrl: String, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val absolute = runCatching { URL(URL(baseUrl), value).toString() }.getOrNull() ?: value
        return absolute.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }
    }'''
pub = replace_once(pub, helper_anchor, helper_new, "public image resolver")
write(pub_path, pub)


# --- SMSticket: fewer detail pages, shorter waits, more image fallbacks ---
sms_path, sms = read("app/src/main/java/cz/kulturadar/app/data/SmsTicketRepository.kt")
sms = sms.replace("links.take(if (requested != null) 120 else 260)", "links.take(if (requested != null) 36 else 120)", 1)
sms = sms.replace("val pool = Executors.newFixedThreadPool(8)\n        val parsed", "val pool = Executors.newFixedThreadPool(12)\n        val parsed", 1)
sms = sms.replace("f.get(8, TimeUnit.SECONDS)", "f.get(4, TimeUnit.SECONDS)")
sms = replace_once(
    sms,
    'private fun connect(url: String): Document = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Kulturadar/2.2").timeout(8000).followRedirects(true).maxBodySize(3_000_000).get()',
    'private fun connect(url: String): Document = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Kulturadar/1.0").timeout(4500).followRedirects(true).maxBodySize(2_000_000).get()',
    "SMSticket connect timeout",
)
sms = replace_once(
    sms,
    '        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)',
    '        val img = bestDocumentImage(doc, url)',
    "SMSticket fallback image",
)
sms = replace_once(
    sms,
    '        val image = image(j.opt("image")) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)',
    '        val image = resolveImage(url, image(j.opt("image"))) ?: bestDocumentImage(doc, url)',
    "SMSticket JSON image",
)
sms_anchor = '    private fun image(v: Any?): String? = when(v) { is String -> v.takeIf(String::isNotBlank); is JSONArray -> v.optString(0).takeIf(String::isNotBlank); is JSONObject -> v.optString("url").takeIf(String::isNotBlank); else -> null }\n'
sms_helpers = sms_anchor + '''    private fun resolveImage(baseUrl: String, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val absolute = runCatching { URL(URL(baseUrl), value).toString() }.getOrNull() ?: value
        return absolute.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }
    }
    private fun bestDocumentImage(doc: Document, baseUrl: String): String? =
        resolveImage(baseUrl, doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: resolveImage(baseUrl, doc.selectFirst("meta[name=twitter:image]")?.attr("content"))
            ?: resolveImage(baseUrl, doc.selectFirst("link[rel=image_src]")?.attr("href"))
            ?: resolveImage(baseUrl, doc.selectFirst("img[data-src], img[data-lazy-src], img[src]")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            })
'''
sms = replace_once(sms, sms_anchor, sms_helpers, "SMSticket image helpers")
write(sms_path, sms)


# --- GoOut: when a city is selected only that city is queried; trim detail work further ---
go_path, go = read("app/src/main/java/cz/kulturadar/app/data/GoOutRepository.kt")
go = go.replace(".take(30)", ".take(18)", 1)
go = go.replace("future.get(8, TimeUnit.SECONDS)", "future.get(4, TimeUnit.SECONDS)")
go = go.replace(".timeout(7000)", ".timeout(4500)", 1)
write(go_path, go)

print("Kulturadar patch applied")
