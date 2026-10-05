package cz.kulturadar.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cz.kulturadar.app.BuildConfig
import cz.kulturadar.app.data.CzechPlace
import cz.kulturadar.app.data.EventCache
import cz.kulturadar.app.data.EventRepository
import cz.kulturadar.app.data.PlaceRepository
import cz.kulturadar.app.data.ReactionStore
import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import cz.kulturadar.app.model.Reaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ProMode(val key: String, val label: String) { SWIPE("swipe", "Swipe"), LIST("cards", "Seznam") }
private enum class ProTab(val label: String) { DISCOVER("Objevovat"), SAVED("Uložené"), HIDDEN("Skryté"), SETTINGS("Nastavení") }
private enum class ProSort(val label: String) { SMART("Pro tebe"), DATE("Nejdřív"), DISTANCE("Nejblíž"), PRICE("Nejlevnější") }
private enum class ProWhen(val label: String) { ANY("Kdykoliv"), TODAY("Dnes"), TOMORROW("Zítra"), WEEKEND("Víkend"), WEEK("7 dní") }

@Composable
fun KulturadarProApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var ready by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
    if (!ready) {
        ProSetup { place, radius, mode ->
            prefs.edit()
                .putBoolean("onboarding_done", true)
                .putString("home_place", place.name)
                .putString("home_place_detail", place.detail)
                .putLong("home_lat_bits", java.lang.Double.doubleToRawLongBits(place.latitude))
                .putLong("home_lon_bits", java.lang.Double.doubleToRawLongBits(place.longitude))
                .putInt("radius_km", radius)
                .putString("view_mode", mode.key)
                .apply()
            ready = true
        }
        return
    }
    ProMain(onChangePlace = {
        prefs.edit().putBoolean("onboarding_done", false).apply()
        ready = false
    })
}

@Composable
private fun ProSetup(onDone: (CzechPlace, Int, ProMode) -> Unit) {
    val places = remember { PlaceRepository() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CzechPlace>>(emptyList()) }
    var selected by remember { mutableStateOf<CzechPlace?>(null) }
    var radius by remember { mutableIntStateOf(30) }
    var mode by remember { mutableStateOf(ProMode.SWIPE) }
    var loading by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF030705)) {
        LazyColumn(contentPadding = PaddingValues(24.dp, 36.dp, 24.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFF123C24), modifier = Modifier.size(62.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Radar, null, tint = Color(0xFF61F59A), modifier = Modifier.size(34.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kulturadar", fontSize = 35.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Text("Akce, které má smysl vidět", color = Color(0xFF9EB7A7))
                    }
                }
            }
            item {
                Text("Jak chceš objevovat?", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProMode.entries.forEach { item ->
                        FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) }, leadingIcon = { Icon(if (item == ProMode.SWIPE) Icons.Default.Swipe else Icons.Default.ViewAgenda, null) })
                    }
                }
            }
            item {
                Text("Odkud vyrážíš?", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Najdi libovolné město, obec nebo vesnici v Česku.", color = Color(0xFF9EB7A7))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (query.trim().length >= 2 && !loading) {
                                loading = true
                                scope.launch {
                                    results = withContext(Dispatchers.IO) { places.search(query) }
                                    loading = false
                                }
                            }
                        }) { Icon(Icons.Default.Search, "Vyhledat") }
                    },
                    placeholder = { Text("Rychvald, Ostrava, Bílá…") },
                    shape = RoundedCornerShape(22.dp)
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            items(results, key = { "${it.name}-${it.latitude}-${it.longitude}" }) { place ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selected = place },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected == place) Color(0xFF123C24) else Color(0xFF0B120E)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = Color(0xFF61F59A))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.name, color = Color.White, fontWeight = FontWeight.Bold)
                            if (place.detail.isNotBlank()) Text(place.detail, color = Color(0xFF9EB7A7), fontSize = 12.sp)
                        }
                        if (selected == place) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF61F59A))
                    }
                }
            }
            item {
                Text("Jak daleko?", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Akce do $radius km", color = Color(0xFF61F59A), fontWeight = FontWeight.Bold)
                Slider(value = radius.toFloat(), onValueChange = { radius = it.roundToInt() }, valueRange = 5f..200f, steps = 38)
                Text("Vzdálenost můžeš kdykoliv změnit v Nastavení.", color = Color(0xFF9EB7A7), fontSize = 12.sp)
            }
            item {
                Button(onClick = { selected?.let { onDone(it, radius, mode) } }, enabled = selected != null, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Default.Explore, null); Spacer(Modifier.width(8.dp)); Text("Najít akce")
                }
            }
        }
    }
}

@Composable
private fun ProMain(onChangePlace: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val repo = remember { EventRepository() }
    val cache = remember { EventCache(context) }
    val reactions = remember { ReactionStore(context) }
    val scope = rememberCoroutineScope()
    val premium = BuildConfig.INTERNAL_PREMIUM

    val home = prefs.getString("home_place", "Ostrava") ?: "Ostrava"
    val homeDetail = prefs.getString("home_place_detail", "") ?: ""
    val homeLat = java.lang.Double.longBitsToDouble(prefs.getLong("home_lat_bits", java.lang.Double.doubleToRawLongBits(49.8209)))
    val homeLon = java.lang.Double.longBitsToDouble(prefs.getLong("home_lon_bits", java.lang.Double.doubleToRawLongBits(18.2625)))

    var radius by remember { mutableIntStateOf(prefs.getInt("radius_km", 30)) }
    var mode by remember { mutableStateOf(if (prefs.getString("view_mode", "swipe") == "cards") ProMode.LIST else ProMode.SWIPE) }
    var tab by remember { mutableStateOf(ProTab.DISCOVER) }
    var events by remember { mutableStateOf(cache.load().ifEmpty { repo.demoEvents() }) }
    var search by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(prefs.getString("tm_key", "") ?: "") }
    var whenFilter by remember { mutableStateOf(ProWhen.ANY) }
    var type by remember { mutableStateOf(EventType.ALL) }
    var sort by remember { mutableStateOf(ProSort.SMART) }
    var maxPrice by remember { mutableStateOf<Int?>(null) }
    var onlyWithImage by remember { mutableStateOf(false) }
    var freeOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    fun distance(event: CulturalEvent): Double? {
        val lat = event.latitude ?: return null
        val lon = event.longitude ?: return null
        return PlaceRepository.distanceKm(homeLat, homeLon, lat, lon)
    }

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { repo.loadAllSources(apiKey.trim(), "Všechna města", null) }
            val nearby = result.events.filter { e -> distance(e)?.let { it <= radius } ?: e.city.equals(home, true) }
            if (nearby.isNotEmpty()) {
                events = nearby
                withContext(Dispatchers.IO) { cache.save(nearby) }
            }
            message = when {
                nearby.isEmpty() && events.isNotEmpty() -> "Živá data se teď nepodařila obnovit. Zobrazuji poslední uložené akce."
                nearby.isEmpty() -> "V okruhu $radius km od $home teď nic není. Zkus větší vzdálenost."
                else -> result.error
            }
            loading = false
        }
    }

    LaunchedEffect(home, radius) { refresh() }
    BackHandler(enabled = selected != null || tab != ProTab.DISCOVER) { if (selected != null) selected = null else tab = ProTab.DISCOVER }

    val visible = remember(events, search, tab, tick, whenFilter, type, sort, maxPrice, onlyWithImage, freeOnly, radius) {
        val loved = events.filter { reactions.get(it.id) == Reaction.LOVED }
        val hated = events.filter { reactions.get(it.id) == Reaction.HATED }
        val avgPrice = loved.mapNotNull { it.priceCzk }.takeIf { it.isNotEmpty() }?.average()

        fun smartScore(e: CulturalEvent): Int {
            if (!premium) return 0
            var s = 0
            s += loved.count { it.type == e.type } * 8
            s += loved.count { it.genre != null && it.genre.equals(e.genre, true) } * 7
            s -= hated.count { it.type == e.type } * 4
            distance(e)?.let { s += (20 - it.coerceAtMost(20.0)).roundToInt() }
            if (avgPrice != null && e.priceCzk != null && abs(e.priceCzk - avgPrice) <= 300) s += 3
            if (!e.imageUrl.isNullOrBlank() && !e.imageIsFallback) s += 2
            return s
        }

        val filtered = events.filter { e ->
            val reaction = reactions.get(e.id)
            val tabOk = when (tab) {
                ProTab.SAVED -> reaction == Reaction.LOVED
                ProTab.HIDDEN -> reaction == Reaction.HATED
                else -> reaction != Reaction.HATED
            }
            val q = search.trim()
            val qOk = q.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city, e.genre.orEmpty()).any { it.contains(q, true) }
            val typeOk = type == EventType.ALL || e.type == type
            val whenOk = proDateMatches(e.dateLabel, whenFilter)
            val priceOk = maxPrice == null || (e.priceCzk ?: Int.MAX_VALUE) <= maxPrice!!
            val freeOk = !freeOnly || e.priceCzk == 0
            val imageOk = !onlyWithImage || (!e.imageUrl.isNullOrBlank() && !e.imageIsFallback)
            tabOk && qOk && typeOk && whenOk && priceOk && freeOk && imageOk
        }
        when (sort) {
            ProSort.SMART -> filtered.sortedByDescending(::smartScore)
            ProSort.DATE -> filtered.sortedBy { it.dateLabel + it.timeLabel }
            ProSort.DISTANCE -> filtered.sortedBy { distance(it) ?: Double.MAX_VALUE }
            ProSort.PRICE -> filtered.sortedBy { it.priceCzk ?: Int.MAX_VALUE }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF2050907), tonalElevation = 0.dp) {
                ProTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null },
                        icon = { Icon(when (item) { ProTab.DISCOVER -> Icons.Default.Explore; ProTab.SAVED -> Icons.Default.Favorite; ProTab.HIDDEN -> Icons.Default.VisibilityOff; ProTab.SETTINGS -> Icons.Default.Settings }, null) },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> ProDetail(selected!!, reactions.get(selected!!.id), distance(selected!!), onBack = { selected = null }, onReact = { reactions.set(selected!!.id, it); tick++ }, modifier = Modifier.padding(padding))
            tab == ProTab.SETTINGS -> ProSettings(home, homeDetail, radius, { radius = it; prefs.edit().putInt("radius_km", it).apply() }, mode, { mode = it; prefs.edit().putString("view_mode", it.key).apply() }, apiKey, { apiKey = it }, { prefs.edit().putString("tm_key", apiKey.trim()).apply(); refresh() }, onChangePlace, modifier = Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                ProHeader(home, radius, search, { search = it }, loading, ::refresh, premium)
                ProControls(whenFilter, { whenFilter = it }, type, { type = it }, sort, { sort = it }, freeOnly, { freeOnly = it }, onlyWithImage, { onlyWithImage = it }, maxPrice, { maxPrice = it }, premium)
                message?.let { ProMessage(it) }
                if (visible.isNotEmpty() && tab == ProTab.DISCOVER) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visible.size} tipů", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = visible.random() }) { Icon(Icons.Default.Casino, null); Spacer(Modifier.width(5.dp)); Text("Překvap mě") }
                    }
                }
                if (mode == ProMode.SWIPE && tab == ProTab.DISCOVER) {
                    ProSwipe(visible, ::distance, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); tick++ }, Modifier.weight(1f))
                } else {
                    ProList(visible, reactions, ::distance, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); tick++ }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProHeader(place: String, radius: Int, search: String, onSearch: (String) -> Unit, loading: Boolean, onRefresh: () -> Unit, premium: Boolean) {
    Column(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kulturadar", fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                    if (premium) { Spacer(Modifier.width(8.dp)); Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(9.dp)) { Text("PREMIUM", Modifier.padding(7.dp, 3.dp), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Black) } }
                }
                Text("$place · do $radius km", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Obnovit") }
        }
        OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Akce, interpret, místo…") }, shape = RoundedCornerShape(24.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun ProControls(whenValue: ProWhen, onWhen: (ProWhen) -> Unit, type: EventType, onType: (EventType) -> Unit, sort: ProSort, onSort: (ProSort) -> Unit, freeOnly: Boolean, onFree: (Boolean) -> Unit, imageOnly: Boolean, onImage: (Boolean) -> Unit, maxPrice: Int?, onPrice: (Int?) -> Unit, premium: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun ProMessage(text: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp)); Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProSwipe(events: List<CulturalEvent>, distance: (CulturalEvent) -> Double?, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    if (events.isEmpty() || index >= events.size) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Radar, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(10.dp)); Text(if (events.isEmpty()) "Pro tento výběr tu nic není." else "Prošel jsi všechny tipy.") }
        }
        return
    }
    val e = events[index]
    fun next(r: Reaction) { onReact(e.id, r); dragX = 0f; index++ }
    Column(modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).graphicsLayer { translationX = dragX; rotationZ = dragX / 45f }
                .pointerInput(e.id) { detectDragGestures(onDragEnd = { when { dragX > 120f -> next(Reaction.LOVED); dragX < -120f -> next(Reaction.HATED); else -> dragX = 0f } }) { change, amount -> change.consume(); dragX += amount.x } }
                .clickable { onOpen(e) },
            shape = RoundedCornerShape(32.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                ProImage(e, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ProBadge(e.genre ?: e.type.title)
                    distance(e)?.let { ProBadge("${it.roundToInt()} km") }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(e.title, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${prettyDate(e.dateLabel)} ${e.timeLabel}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("${e.city} · ${e.venue}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(priceText(e), fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            FilledTonalIconButton(onClick = { next(Reaction.HATED) }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Close, "Nechci", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(34.dp)) }
            FilledTonalIconButton(onClick = { onOpen(e) }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.Info, "Detail") }
            FilledTonalIconButton(onClick = { next(Reaction.LOVED) }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Favorite, "Uložit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProList(events: List<CulturalEvent>, reactions: ReactionStore, distance: (CulturalEvent) -> Double?, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pro tento výběr tu nic není.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(events, key = { it.id }) { e ->
            val r = reactions.get(e.id)
            Card(Modifier.fillMaxWidth().clickable { onOpen(e) }, shape = RoundedCornerShape(28.dp)) {
                Box(Modifier.fillMaxWidth().height(250.dp)) {
                    ProImage(e, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0050507)))))
                    Row(Modifier.align(Alignment.TopStart).padding(13.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProBadge(e.genre ?: e.type.title); distance(e)?.let { ProBadge("${it.roundToInt()} km") }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(e.title, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${prettyDate(e.dateLabel)} ${e.timeLabel} · ${e.city}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.venue, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${priceText(e)} · ${e.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    IconButton(onClick = { onReact(e.id, if (r == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) { Icon(Icons.Default.VisibilityOff, "Skrýt") }
                    FilledTonalIconButton(onClick = { onReact(e.id, if (r == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) { Icon(Icons.Default.Favorite, "Uložit", tint = if (r == Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun ProBadge(text: String) {
    Surface(color = Color(0xD008100B), shape = RoundedCornerShape(12.dp)) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
}

@Composable
private fun ProImage(e: CulturalEvent, modifier: Modifier = Modifier) {
    if (!e.imageUrl.isNullOrBlank()) AsyncImage(model = e.imageUrl, contentDescription = e.title, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color(0xFF102218), Color(0xFF050706)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ImageNotSupported, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(7.dp)); Text("Bez obrázku od pořadatele", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProDetail(e: CulturalEvent, reaction: Reaction, distanceKm: Double?, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(355.dp)) {
                ProImage(e, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5050507)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { ProBadge(e.genre ?: e.type.title); distanceKm?.let { ProBadge("${it.roundToInt()} km") } }
                    Spacer(Modifier.height(8.dp)); Text(e.title, fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProInfo(Icons.Default.CalendarMonth, "${prettyDate(e.dateLabel)} ${e.timeLabel}")
                ProInfo(Icons.Default.LocationOn, listOf(e.venue, e.city).filter { it.isNotBlank() }.joinToString(", "))
                ProInfo(Icons.Default.Payments, priceText(e))
                if (!e.status.isNullOrBlank()) ProInfo(Icons.Default.Info, statusText(e.status))
                HorizontalDivider()
                Text("O akci", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(e.description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.VisibilityOff, null); Spacer(Modifier.width(5.dp)); Text("Nechci") }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(5.dp)); Text("Uložit") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { share(context, e) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("Sdílet") }
                    OutlinedButton(onClick = { addCalendar(context, e) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Event, null); Spacer(Modifier.width(5.dp)); Text("Kalendář") }
                }
                if (e.latitude != null && e.longitude != null) OutlinedButton(onClick = { openMap(context, e) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(7.dp)); Text("Navigovat na místo") }
                e.ticketUrl?.let { url -> Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) { Text("Vstupenky / oficiální detail"); Spacer(Modifier.width(7.dp)); Icon(Icons.Default.OpenInNew, null) } }
                Text("Zdroj: ${e.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ProSettings(place: String, detail: String, radius: Int, onRadius: (Int) -> Unit, mode: ProMode, onMode: (ProMode) -> Unit, apiKey: String, onApiKey: (String) -> Unit, onSaveApi: () -> Unit, onChangePlace: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var localRadius by remember(radius) { mutableIntStateOf(radius) }
    val accents = listOf("green" to Color(0xFF61F59A), "wine" to Color(0xFFFF5E89), "purple" to Color(0xFFC985FF), "blue" to Color(0xFF72A7FF), "red" to Color(0xFFFF6472), "gold" to Color(0xFFE4BC64))

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Premium aktivní", fontSize = 21.sp, fontWeight = FontWeight.Black) }
                    Text("Smart řazení podle toho, co ukládáš, vzdálenost, cenové filtry, víkendové tipy, náhodný tip, offline cache a rychlé plánování.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Domovská lokalita", fontWeight = FontWeight.Bold)
            Text(place, color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Black)
            if (detail.isNotBlank()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp)); Text("Okruh $localRadius km")
            Slider(value = localRadius.toFloat(), onValueChange = { localRadius = it.roundToInt() }, onValueChangeFinished = { onRadius(localRadius) }, valueRange = 5f..200f, steps = 38)
            OutlinedButton(onClick = onChangePlace, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.EditLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Změnit město / vesnici") }
        }
        item {
            Text("Zobrazení", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ProMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { onMode(item) }, label = { Text(item.label) }) } }
        }
        item {
            Text("Barva aplikace", fontWeight = FontWeight.Bold)
            Text("Výchozí zůstává zelená.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                accents.forEach { (id, color) -> Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable { prefs.edit().putString("accent_theme", id).apply(); (context as? Activity)?.recreate() }) }
            }
        }
        item {
            Text("Zdroje akcí", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("GoOut funguje bez API. Ticketmaster je pouze volitelný doplňkový zdroj.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(apiKey, onApiKey, label = { Text("Ticketmaster API key (volitelné)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp)); Button(onClick = onSaveApi, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(7.dp)); Text("Uložit a obnovit zdroje") }
        }
        item { Text("Kulturadar 2.0", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun proDateMatches(date: String, whenValue: ProWhen): Boolean {
    if (whenValue == ProWhen.ANY) return true
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val parsed = runCatching { fmt.parse(date) }.getOrNull() ?: return false
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { time = parsed }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when (whenValue) {
        ProWhen.TODAY -> sameDay(now, target)
        ProWhen.TOMORROW -> sameDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }, target)
        ProWhen.WEEK -> target.timeInMillis in now.timeInMillis..Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
        ProWhen.WEEKEND -> {
            val start = Calendar.getInstance().apply {
                val d = get(Calendar.DAY_OF_WEEK)
                val add = when (d) { Calendar.SATURDAY -> 0; Calendar.SUNDAY -> -1; else -> Calendar.SATURDAY - d }
                add(Calendar.DAY_OF_YEAR, add)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val end = Calendar.getInstance().apply { timeInMillis = start.timeInMillis; add(Calendar.DAY_OF_YEAR, 2) }
            target.timeInMillis in start.timeInMillis until end.timeInMillis
        }
        else -> true
    }
}

private fun prettyDate(date: String): String {
    val input = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val output = SimpleDateFormat("EEE d. M.", Locale("cs", "CZ"))
    return runCatching { input.parse(date)?.let(output::format) }.getOrNull() ?: date
}

private fun priceText(e: CulturalEvent): String {
    val min = e.priceCzk
    val max = e.priceMaxCzk
    return when {
        min == 0 && (max == null || max == 0) -> "Zdarma"
        min != null && max != null && max > min -> "$min–$max Kč"
        min != null -> "od $min Kč"
        else -> "Cena neuvedena"
    }
}

private fun statusText(status: String): String = when (status.lowercase()) {
    "onsale", "instock", "available" -> "V prodeji"
    "offsale", "soldout", "outofstock" -> "Vyprodáno / mimo prodej"
    "cancelled", "canceled" -> "Zrušeno"
    "postponed" -> "Přesunuto"
    else -> status
}

private fun share(context: Context, e: CulturalEvent) {
    val text = buildString { append(e.title).append("\n").append(prettyDate(e.dateLabel)).append(" ").append(e.timeLabel).append(" · ").append(e.venue).append(", ").append(e.city); e.ticketUrl?.let { append("\n").append(it) } }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Sdílet akci"))
}

private fun addCalendar(context: Context, e: CulturalEvent) {
    context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, e.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, "${e.venue}, ${e.city}")
        .putExtra(CalendarContract.Events.DESCRIPTION, e.ticketUrl ?: e.description))
}

private fun openMap(context: Context, e: CulturalEvent) {
    val lat = e.latitude ?: return
    val lon = e.longitude ?: return
    val label = Uri.encode(e.venue)
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
}
