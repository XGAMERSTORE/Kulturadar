package cz.kulturadar.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import cz.kulturadar.app.data.EventCache
import cz.kulturadar.app.data.EventRepository
import cz.kulturadar.app.data.EventWeather
import cz.kulturadar.app.data.ReactionStore
import cz.kulturadar.app.data.WeatherRepository
import cz.kulturadar.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class PlayTab(val title: String) {
    DISCOVER("Objevovat"), SAVED("Uložené"), HIDDEN("Skryté"), SETTINGS("Nastavení")
}

private enum class PlayMode(val key: String, val title: String) {
    CARDS("cards", "Přehled"), SWIPE("swipe", "Swipe")
}

private data class PlayAccent(val id: String, val label: String, val color: Color)

private val playAccents = listOf(
    PlayAccent("green", "Zelená", Color(0xFF61F59A)),
    PlayAccent("wine", "Vínová", Color(0xFFFF5E89)),
    PlayAccent("purple", "Fialová", Color(0xFFC985FF)),
    PlayAccent("blue", "Modrá", Color(0xFF72A7FF)),
    PlayAccent("red", "Červená", Color(0xFFFF6472)),
    PlayAccent("gold", "Zlatá", Color(0xFFE4BC64))
)

@Composable
fun KulturadarPlayApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val repo = remember { EventRepository() }
    val cache = remember { EventCache(context) }
    val reactions = remember { ReactionStore(context) }
    val premium = BuildConfig.INTERNAL_PREMIUM

    var tab by remember { mutableStateOf(PlayTab.DISCOVER) }
    var mode by remember {
        mutableStateOf(if (prefs.getString("view_mode", "swipe") == "cards") PlayMode.CARDS else PlayMode.SWIPE)
    }
    var filter by remember { mutableStateOf(EventFilter(city = prefs.getString("city", "Ostrava") ?: "Ostrava")) }
    var filterOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(prefs.getString("tm_key", "") ?: "") }
    var smartMix by remember { mutableStateOf(prefs.getBoolean("smart_mix", true)) }
    var events by remember { mutableStateOf(cache.load().ifEmpty { repo.demoEvents() }) }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableLongStateOf(cache.lastUpdated()) }
    var reactionTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { repo.loadAllSources(apiKey.trim(), filter.city, filter.search) }
            if (result.events.isNotEmpty()) {
                events = result.events
                withContext(Dispatchers.IO) { cache.save(result.events) }
                lastUpdated = cache.lastUpdated()
            }
            message = result.error
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    BackHandler(enabled = selected != null || filterOpen || tab != PlayTab.DISCOVER) {
        when {
            selected != null -> selected = null
            filterOpen -> filterOpen = false
            tab != PlayTab.DISCOVER -> tab = PlayTab.DISCOVER
        }
    }

    val cities = remember(events) {
        (listOf("Všechna města", "Ostrava", "Praha", "Brno", "Olomouc", "Opava", "Frýdek-Místek", "Plzeň", "Pardubice", "Zlín") + events.map { it.city })
            .filter { it.isNotBlank() }.distinct()
    }
    val genres = remember(events) { events.mapNotNull { it.genre }.filter { it.isNotBlank() }.distinct().sorted() }

    val visible = remember(events, filter, tab, reactionTick, premium, smartMix) {
        val loved = events.filter { reactions.get(it.id) == Reaction.LOVED }
        val hated = events.filter { reactions.get(it.id) == Reaction.HATED }
        val avgPrice = loved.mapNotNull { it.priceCzk }.takeIf { it.isNotEmpty() }?.average()

        fun score(e: CulturalEvent): Int {
            if (!premium || !smartMix) return 0
            var value = 0
            value += loved.count { it.type == e.type } * 8
            value += loved.count { it.city.equals(e.city, true) } * 5
            value += loved.count { it.genre != null && it.genre.equals(e.genre, true) } * 7
            value -= hated.count { it.type == e.type } * 4
            if (avgPrice != null && e.priceCzk != null && abs(e.priceCzk - avgPrice) <= 300) value += 3
            if (!e.imageUrl.isNullOrBlank() && !e.imageIsFallback) value += 2
            return value
        }

        events.filter { e ->
            val reaction = reactions.get(e.id)
            val tabOk = when (tab) {
                PlayTab.SAVED -> reaction == Reaction.LOVED
                PlayTab.HIDDEN -> reaction == Reaction.HATED
                else -> true
            }
            val hiddenOk = tab != PlayTab.DISCOVER || !filter.hideDisliked || reaction != Reaction.HATED
            val typeOk = filter.type == EventType.ALL || e.type == filter.type
            val cityOk = filter.city == "Všechna města" || e.city.equals(filter.city, true)
            val freeOk = !filter.freeOnly || e.priceCzk == 0
            val priceOk = filter.maxPrice == null || (e.priceCzk ?: Int.MAX_VALUE) <= filter.maxPrice!!
            val imageOk = !filter.onlyWithRealImage || (!e.imageUrl.isNullOrBlank() && !e.imageIsFallback)
            val statusOk = !filter.hideUnavailable || isEventAvailable(e.status)
            val genreOk = filter.genre.isNullOrBlank() || e.genre.equals(filter.genre, true)
            val q = filter.search.trim()
            val searchOk = q.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city, e.genre.orEmpty()).any { it.contains(q, true) }
            tabOk && hiddenOk && typeOk && cityOk && freeOk && priceOk && imageOk && statusOk && genreOk && searchOk && playDateMatches(e.dateLabel, filter.date)
        }.let { list ->
            if (tab == PlayTab.DISCOVER && smartMix) list.sortedByDescending(::score)
            else list.sortedBy { it.dateLabel + it.timeLabel }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF2050907), tonalElevation = 0.dp) {
                PlayTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null; filterOpen = false },
                        icon = {
                            Icon(
                                when (item) {
                                    PlayTab.DISCOVER -> Icons.Default.Explore
                                    PlayTab.SAVED -> Icons.Default.Favorite
                                    PlayTab.HIDDEN -> Icons.Default.VisibilityOff
                                    PlayTab.SETTINGS -> Icons.Default.Settings
                                }, null
                            )
                        },
                        label = { Text(item.title, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> PlayDetail(
                event = selected!!,
                reaction = reactions.get(selected!!.id),
                premium = premium,
                onBack = { selected = null },
                onReact = { reactions.set(selected!!.id, it); reactionTick++ },
                modifier = Modifier.padding(padding)
            )

            tab == PlayTab.SETTINGS -> PlaySettings(
                apiKey = apiKey,
                onApiKey = { apiKey = it },
                mode = mode,
                onMode = {
                    mode = it
                    prefs.edit().putString("view_mode", it.key).apply()
                },
                smartMix = smartMix,
                onSmartMix = {
                    smartMix = it
                    prefs.edit().putBoolean("smart_mix", it).apply()
                },
                premium = premium,
                lastUpdated = lastUpdated,
                onSave = {
                    prefs.edit().putString("tm_key", apiKey.trim()).apply()
                    refresh()
                },
                onClearCache = {
                    cache.clear()
                    events = repo.demoEvents()
                    lastUpdated = 0L
                },
                modifier = Modifier.padding(padding)
            )

            tab == PlayTab.DISCOVER && mode == PlayMode.SWIPE -> PlaySwipe(
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                message = message,
                premium = premium,
                cities = cities,
                genres = genres,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = {
                    filter = it
                    prefs.edit().putString("city", it.city).apply()
                },
                onOpen = { selected = it },
                onReact = { id, r -> reactions.set(id, r); reactionTick++ },
                onRefresh = ::refresh,
                modifier = Modifier.padding(padding)
            )

            else -> PlayList(
                title = if (tab == PlayTab.DISCOVER) "Kulturadar" else tab.title,
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                message = message,
                premium = premium,
                cities = cities,
                genres = genres,
                reactionFor = reactions::get,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = {
                    filter = it
                    prefs.edit().putString("city", it.city).apply()
                },
                onOpen = { selected = it },
                onReact = { id, r -> reactions.set(id, r); reactionTick++ },
                onRefresh = ::refresh,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun PlayHeader(
    title: String,
    filter: EventFilter,
    loading: Boolean,
    premium: Boolean,
    onRefresh: () -> Unit,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                    if (premium) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                            Text("PREMIUM", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Text("GoOut bez klíče + volitelný Ticketmaster", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Obnovit") }
            FilledTonalIconButton(onClick = onFilterOpen, modifier = Modifier.size(50.dp)) { Icon(Icons.Default.Tune, "Filtry") }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = filter.search,
            onValueChange = { onFilter(filter.copy(search = it)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Hledat akci, místo, město…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EventType.entries.forEach { type ->
                FilterChip(selected = filter.type == type, onClick = { onFilter(filter.copy(type = type)) }, label = { Text(type.title) })
            }
        }
        if (loading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PlayFilterPanel(
    filter: EventFilter,
    premium: Boolean,
    cities: List<String>,
    genres: List<String>,
    onFilter: (EventFilter) -> Unit
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtry", fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = EventFilter(city = "Ostrava", search = filter.search); onFilter(draft) }) { Text("Reset") }
            }

            Text("Lokalita", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cities.forEach { city -> FilterChip(selected = draft.city == city, onClick = { draft = draft.copy(city = city) }, label = { Text(city) }) }
            }

            Text("Kdy", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kdykoliv", "Dnes", "Zítra", "Tento týden").forEach { date ->
                    FilterChip(selected = draft.date == date, onClick = { draft = draft.copy(date = date) }, label = { Text(date) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(draft.freeOnly, { draft = draft.copy(freeOnly = it) })
                Spacer(Modifier.width(8.dp)); Text("Jen zdarma")
            }

            if (premium) {
                if (genres.isNotEmpty()) {
                    Text("Žánr", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.genre == null, onClick = { draft = draft.copy(genre = null) }, label = { Text("Vše") })
                        genres.take(20).forEach { genre ->
                            FilterChip(selected = draft.genre == genre, onClick = { draft = draft.copy(genre = genre) }, label = { Text(genre) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.onlyWithRealImage, { draft = draft.copy(onlyWithRealImage = it) })
                    Spacer(Modifier.width(8.dp)); Text("Jen akce s obrázkem")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.hideDisliked, { draft = draft.copy(hideDisliked = it) })
                    Spacer(Modifier.width(8.dp)); Text("Skrývat odmítnuté")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.hideUnavailable, { draft = draft.copy(hideUnavailable = it) })
                    Spacer(Modifier.width(8.dp)); Text("Skrývat zrušené / nedostupné")
                }
                Text("Maximální cena: ${draft.maxPrice?.let { "$it Kč" } ?: "bez limitu"}")
                Slider(
                    value = (draft.maxPrice ?: 5000).toFloat(),
                    onValueChange = { draft = draft.copy(maxPrice = it.toInt()) },
                    valueRange = 0f..5000f,
                    steps = 19
                )
                TextButton(onClick = { draft = draft.copy(maxPrice = null) }) { Text("Bez cenového limitu") }
            }
            Button(onClick = { onFilter(draft) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Použít filtry") }
        }
    }
}

@Composable
private fun PlaySwipe(
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    message: String?,
    premium: Boolean,
    cities: List<String>,
    genres: List<String>,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onReact: (String, Reaction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PlayHeader("Kulturadar", filter, loading, premium, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { PlayFilterPanel(filter, premium, cities, genres, onFilter) }
        message?.let { PlayMessage(it) }

        if (events.isEmpty() || index >= events.size) {
            PlayEmpty(if (events.isEmpty()) "Pro tento výběr tu teď nic není." else "Prošel jsi všechny akce.", onRefresh, Modifier.fillMaxSize())
            return@Column
        }

        val event = events[index]
        fun next(r: Reaction) { onReact(event.id, r); dragX = 0f; index++ }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .graphicsLayer { translationX = dragX; rotationZ = dragX / 45f }
                    .pointerInput(event.id) {
                        detectDragGestures(onDragEnd = {
                            when {
                                dragX > 120f -> next(Reaction.LOVED)
                                dragX < -120f -> next(Reaction.HATED)
                                else -> dragX = 0f
                            }
                        }) { change, amount -> change.consume(); dragX += amount.x }
                    }
                    .clickable { onOpen(event) },
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(Modifier.fillMaxSize()) {
                    PlayVisual(event, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                    Surface(Modifier.align(Alignment.TopStart).padding(18.dp), color = Color(0xCC07100B), shape = RoundedCornerShape(14.dp)) {
                        Text(event.genre ?: event.type.title, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text(event.title, fontSize = 31.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text("${playPrettyDate(event.dateLabel)} ${event.timeLabel}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("${event.city} · ${event.venue}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(playPriceText(event), fontWeight = FontWeight.Bold)
                        Text(event.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { next(Reaction.HATED) }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Close, "Nechci", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(34.dp)) }
                FilledTonalIconButton(onClick = { onOpen(event) }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.Info, "Detail") }
                FilledTonalIconButton(onClick = { next(Reaction.LOVED) }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Favorite, "Uložit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PlayList(
    title: String,
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    message: String?,
    premium: Boolean,
    cities: List<String>,
    genres: List<String>,
    reactionFor: (String) -> Reaction,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onReact: (String, Reaction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PlayHeader(title, filter, loading, premium, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { PlayFilterPanel(filter, premium, cities, genres, onFilter) }
        message?.let { PlayMessage(it) }
        if (events.isEmpty()) {
            PlayEmpty("Nic tu zatím není.", onRefresh, Modifier.fillMaxSize())
        } else {
            LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(events, key = { it.id }) { event -> PlayCard(event, reactionFor(event.id), onReact, onOpen) }
            }
        }
    }
}

@Composable
private fun PlayCard(event: CulturalEvent, reaction: Reaction, onReact: (String, Reaction) -> Unit, onOpen: (CulturalEvent) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(event) }, shape = RoundedCornerShape(28.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            PlayVisual(event, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0050507)))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(event.title, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${playPrettyDate(event.dateLabel)} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.venue, fontWeight = FontWeight.Bold)
                Text(event.genre ?: event.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(playPriceText(event), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(event.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onReact(event.id, if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) {
                Icon(Icons.Default.VisibilityOff, "Skrýt", tint = if (reaction == Reaction.HATED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { onReact(event.id, if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) {
                Icon(Icons.Default.Favorite, "Uložit", tint = if (reaction == Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlayVisual(event: CulturalEvent, modifier: Modifier = Modifier) {
    if (!event.imageUrl.isNullOrBlank()) {
        AsyncImage(model = event.imageUrl, contentDescription = event.title, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color(0xFF102218), Color(0xFF050706))))) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ImageNotSupported, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Pořadatel neposkytl obrázek", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlayDetail(
    event: CulturalEvent,
    reaction: Reaction,
    premium: Boolean,
    onBack: () -> Unit,
    onReact: (Reaction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val weatherRepo = remember { WeatherRepository() }
    var weather by remember(event.id) { mutableStateOf<EventWeather?>(null) }

    LaunchedEffect(event.id) {
        if (premium && event.latitude != null && event.longitude != null) {
            weather = withContext(Dispatchers.IO) { weatherRepo.forecast(event.latitude, event.longitude, event.dateLabel) }
        }
    }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                PlayVisual(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF050706)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text((event.genre ?: event.type.title).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(event.title, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                PlayInfo(Icons.Default.CalendarMonth, "${playPrettyDate(event.dateLabel)} ${event.timeLabel}")
                PlayInfo(Icons.Default.LocationOn, playLocation(event))
                PlayInfo(Icons.Default.Payments, playPriceText(event))
                event.status?.let { PlayInfo(Icons.Default.Info, "Stav: ${playStatusText(it)}") }

                weather?.let { w ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Počasí na den akce", fontWeight = FontWeight.Bold)
                                Text(buildString {
                                    append(w.label)
                                    if (w.minC != null || w.maxC != null) append(" · ${w.minC ?: "?"} až ${w.maxC ?: "?"} °C")
                                    w.precipitationProbability?.let { append(" · déšť $it %") }
                                })
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("O akci", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.VisibilityOff, null); Spacer(Modifier.width(6.dp)); Text("Nechci")
                    }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(6.dp)); Text("Uložit")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { playShare(context, event) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Sdílet")
                    }
                    OutlinedButton(onClick = { playCalendar(context, event) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Event, null); Spacer(Modifier.width(6.dp)); Text("Kalendář")
                    }
                }

                if (event.latitude != null && event.longitude != null) {
                    OutlinedButton(onClick = { playDirections(context, event) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Directions, null); Spacer(Modifier.width(8.dp)); Text("Navigovat na místo")
                    }
                }

                event.ticketUrl?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Vstupenky / detail akce"); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.OpenInNew, null)
                    }
                }

                Text("Zdroj: ${event.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlaySettings(
    apiKey: String,
    onApiKey: (String) -> Unit,
    mode: PlayMode,
    onMode: (PlayMode) -> Unit,
    smartMix: Boolean,
    onSmartMix: (Boolean) -> Unit,
    premium: Boolean,
    lastUpdated: Long,
    onSave: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var accent by remember { mutableStateOf(prefs.getString("accent_theme", "green") ?: "green") }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Výchozí vzhled je znovu zelený Kulturadar", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Barva aplikace", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        playAccents.forEach { item ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(item.color)
                                        .then(if (accent == item.id) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                        .clickable {
                                            accent = item.id
                                            prefs.edit().putString("accent_theme", item.id).apply()
                                            (context as? Activity)?.recreate()
                                        }
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(item.label, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(if (premium) "Premium aktivní" else "Premium", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Smart Mix, žánry, cena, skrývání zrušených akcí, skutečné obrázky, počasí, navigace, kalendář a offline data.")
                }
            }
        }

        item {
            Text("Způsob objevování", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { onMode(item) }, label = { Text(item.title) }) }
            }
        }

        if (premium) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Smart Mix", fontWeight = FontWeight.Bold)
                        Text("Řadí akce podle toho, co ukládáš a odmítáš.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(smartMix, onSmartMix)
                }
            }
        }

        item {
            Text("Zdroje akcí", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("GoOut funguje automaticky bez API klíče. Ticketmaster je volitelný druhý zdroj a přidá další akce, když vložíš klíč.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(apiKey, onApiKey, label = { Text("Ticketmaster API key – volitelné") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Uložit a obnovit všechny zdroje") }
        }

        item {
            Text("Offline data", fontWeight = FontWeight.Bold)
            Text(
                if (lastUpdated > 0) "Poslední aktualizace: ${playUpdated(lastUpdated)}. Poslední reálné akce zůstávají v telefonu i bez připojení."
                else "Po prvním načtení se akce uloží pro offline použití.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lastUpdated > 0) TextButton(onClick = onClearCache) { Text("Smazat offline data") }
        }

        item {
            Text("Kulturadar 1.8", fontWeight = FontWeight.Bold)
            Text("Zelené rozhraní, více zdrojů, data i bez API klíče a volitelná změna barvy v nastavení.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayMessage(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
    }
}

@Composable
private fun PlayEmpty(text: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Radar, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Obnovit") }
        }
    }
}

@Composable
private fun PlayInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

private fun playDateMatches(dateLabel: String, filter: String): Boolean {
    if (filter == "Kdykoliv") return true
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
    val date = runCatching { fmt.parse(dateLabel) }.getOrNull() ?: return false
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (filter) {
        "Dnes" -> fmt.format(date) == fmt.format(start.time)
        "Zítra" -> fmt.format(date) == fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
        "Tento týden" -> {
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
            date.time in start.timeInMillis until end.timeInMillis
        }
        else -> true
    }
}

private fun isEventAvailable(status: String?): Boolean = when (status?.lowercase()) {
    "cancelled", "canceled", "offsale", "soldout" -> false
    else -> true
}

private fun playStatusText(status: String): String = when (status.lowercase()) {
    "onsale", "instock" -> "v prodeji"
    "offsale", "outofstock", "soldout" -> "mimo prodej / vyprodáno"
    "cancelled", "canceled" -> "zrušeno"
    "postponed" -> "odloženo"
    "rescheduled" -> "přesunuto"
    else -> status
}

private fun playPriceText(event: CulturalEvent): String {
    val min = event.priceCzk ?: return "Cena neuvedena"
    val max = event.priceMaxCzk
    val currency = if (event.currency.equals("CZK", true) || event.currency.isNullOrBlank()) "Kč" else event.currency
    return when {
        min == 0 && (max == null || max == 0) -> "Zdarma"
        max != null && max > min -> "$min–$max $currency"
        else -> "od $min $currency"
    }
}

private fun playLocation(event: CulturalEvent): String = buildString {
    append(event.venue)
    event.address?.let { append(", ").append(it) }
    append(", ").append(event.city)
    event.postalCode?.let { append(" ").append(it) }
}

private fun playPrettyDate(dateIso: String): String {
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(dateIso) }.getOrNull() ?: return dateIso
    return SimpleDateFormat("d. M. yyyy", Locale("cs", "CZ")).format(date)
}

private fun playUpdated(value: Long): String = SimpleDateFormat("d. M. HH:mm", Locale("cs", "CZ")).format(Date(value))

private fun playShare(context: Context, event: CulturalEvent) {
    val text = buildString {
        append(event.title).append("\n")
        append(playPrettyDate(event.dateLabel)).append(" ").append(event.timeLabel).append(" · ").append(event.venue).append(", ").append(event.city)
        append("\n").append(playPriceText(event))
        event.ticketUrl?.let { append("\n").append(it) }
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "Sdílet akci"))
}

private fun playCalendar(context: Context, event: CulturalEvent) {
    val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }
    val time = event.timeLabel.ifBlank { "18:00" }
    val startMillis = runCatching { parser.parse("${event.dateLabel} $time")?.time }.getOrNull()
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, playLocation(event))
        .putExtra(CalendarContract.Events.DESCRIPTION, event.ticketUrl ?: event.description)
    if (startMillis != null) {
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 2 * 60 * 60 * 1000L)
    }
    context.startActivity(intent)
}

private fun playDirections(context: Context, event: CulturalEvent) {
    val lat = event.latitude ?: return
    val lon = event.longitude ?: return
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=transit")))
}
