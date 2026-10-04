package cz.kulturadar.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cz.kulturadar.app.data.EventRepository
import cz.kulturadar.app.data.ReactionStore
import cz.kulturadar.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class Tab(val label: String) {
    DISCOVER("Objevovat"), LOVED("Milované"), HATED("Nenáviděné"), SETTINGS("Nastavení")
}

private enum class ViewMode(val value: String, val title: String) {
    CARDS("cards", "Přehled"), SWIPE("swipe", "Swipe")
}

private data class AccentChoice(val id: String, val label: String, val color: Color)

private val accents = listOf(
    AccentChoice("wine", "Vínová", Color(0xFFFF5E89)),
    AccentChoice("green", "Zelená", Color(0xFF61F59A)),
    AccentChoice("purple", "Fialová", Color(0xFFC985FF)),
    AccentChoice("blue", "Modrá", Color(0xFF72A7FF)),
    AccentChoice("red", "Červená", Color(0xFFFF6472)),
    AccentChoice("gold", "Zlatá", Color(0xFFE4BC64))
)

@Composable
fun KulturadarApp() {
    val context = LocalContext.current
    val repo = remember { EventRepository() }
    val store = remember { ReactionStore(context) }
    val settings = remember { context.getSharedPreferences("settings", 0) }
    var viewMode by remember {
        mutableStateOf(settings.getString("view_mode", "swipe")?.let { value ->
            ViewMode.values().firstOrNull { it.value == value }
        } ?: ViewMode.SWIPE)
    }
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var filter by remember { mutableStateOf(EventFilter()) }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(settings.getString("tm_key", "") ?: "") }
    var events by remember { mutableStateOf(repo.demoEvents()) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = selected != null || filterOpen || tab != Tab.DISCOVER) {
        when {
            selected != null -> selected = null
            filterOpen -> filterOpen = false
            tab != Tab.DISCOVER -> tab = Tab.DISCOVER
        }
    }

    val visible = remember(events, filter, tab, reloadTick) {
        val loved = events.filter { store.get(it.id) == Reaction.LOVED }
        val hated = events.filter { store.get(it.id) == Reaction.HATED }
        val averagePrice = loved.mapNotNull { it.priceCzk }.takeIf { it.isNotEmpty() }?.average()

        fun score(event: CulturalEvent): Int {
            var value = 0
            value += loved.count { it.type == event.type } * 6
            value += loved.count { it.city.equals(event.city, true) } * 4
            value -= hated.count { it.type == event.type } * 4
            if (averagePrice != null && event.priceCzk != null && abs(event.priceCzk - averagePrice) <= 250) value += 2
            return value
        }

        events.filter { e ->
            val reaction = store.get(e.id)
            val tabOk = when (tab) {
                Tab.LOVED -> reaction == Reaction.LOVED
                Tab.HATED -> reaction == Reaction.HATED
                else -> true
            }
            val typeOk = filter.type == EventType.ALL || e.type == filter.type
            val cityOk = filter.city == "Všechna města" || e.city.equals(filter.city, true)
            val freeOk = !filter.freeOnly || e.priceCzk == 0
            val dateOk = when (filter.date) {
                "Dnes" -> e.dateLabel.equals("Dnes", true)
                "Zítra" -> e.dateLabel.equals("Zítra", true)
                else -> true
            }
            val priceOk = filter.maxPrice == null || (e.priceCzk ?: Int.MAX_VALUE) <= filter.maxPrice!!
            val q = filter.search.trim()
            val searchOk = q.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city).any { it.contains(q, true) }
            tabOk && typeOk && cityOk && freeOk && dateOk && priceOk && searchOk
        }.let { if (tab == Tab.DISCOVER) it.sortedByDescending(::score) else it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF20A090B), tonalElevation = 0.dp) {
                listOf(Tab.DISCOVER, Tab.LOVED, Tab.HATED, Tab.SETTINGS).forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null; filterOpen = false },
                        icon = {
                            Icon(
                                when (item) {
                                    Tab.DISCOVER -> Icons.Default.Explore
                                    Tab.LOVED -> Icons.Default.Favorite
                                    Tab.HATED -> Icons.Default.ThumbDown
                                    Tab.SETTINGS -> Icons.Default.Settings
                                }, null
                            )
                        },
                        label = { Text(item.label, fontSize = 12.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> EventDetail(
                event = selected!!,
                reaction = store.get(selected!!.id),
                onBack = { selected = null },
                onReact = { store.set(selected!!.id, it); reloadTick++ },
                modifier = Modifier.padding(padding)
            )

            tab == Tab.SETTINGS -> SettingsScreen(
                apiKey = apiKey,
                onKeyChange = { apiKey = it },
                viewMode = viewMode,
                onModeChange = {
                    viewMode = it
                    settings.edit().putString("view_mode", it.value).apply()
                },
                onSave = {
                    settings.edit().putString("tm_key", apiKey).apply()
                    loading = true
                    scope.launch {
                        val live = withContext(Dispatchers.IO) { repo.ticketmaster(apiKey, filter.city, filter.search) }
                        if (live.isNotEmpty()) events = live
                        loading = false
                    }
                },
                modifier = Modifier.padding(padding)
            )

            tab == Tab.DISCOVER && viewMode == ViewMode.SWIPE -> SwipeScreen(
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
                onReact = { id, reaction -> store.set(id, reaction); reloadTick++ },
                onRefresh = {
                    loading = true
                    scope.launch {
                        val live = withContext(Dispatchers.IO) { repo.ticketmaster(apiKey, filter.city, filter.search) }
                        events = if (live.isNotEmpty()) live else repo.demoEvents()
                        loading = false
                    }
                },
                modifier = Modifier.padding(padding)
            )

            else -> CardListScreen(
                title = when (tab) {
                    Tab.LOVED -> "Milované"
                    Tab.HATED -> "Nenáviděné"
                    else -> "Kulturadar"
                },
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
                reactionFor = store::get,
                onReact = { id, reaction -> store.set(id, reaction); reloadTick++ },
                onRefresh = {},
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun Header(
    title: String,
    filter: EventFilter,
    loading: Boolean,
    onRefresh: () -> Unit,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                Text("Objevuj kulturu po celé ČR", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Obnovit") }
            FilledTonalIconButton(onClick = onFilterOpen, modifier = Modifier.size(50.dp)) {
                Icon(Icons.Default.Tune, "Filtry")
            }
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
        TypeRail(filter.type) { onFilter(filter.copy(type = it)) }
        if (loading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TypeRail(selected: EventType, onSelect: (EventType) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EventType.ALL, EventType.EVENT, EventType.THEATRE, EventType.CINEMA, EventType.CONCERT, EventType.EXHIBITION).forEach { type ->
            FilterChip(selected = selected == type, onClick = { onSelect(type) }, label = { Text(type.title) })
        }
    }
}

@Composable
private fun SwipeScreen(
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
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
        Header("Kulturadar", filter, loading, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { DatingFilterPanel(filter, onFilter) }

        if (events.isEmpty() || index >= events.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Prošel jsi všechny akce", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { index = 0 }) { Text("Začít znovu") }
                }
            }
            return@Column
        }

        val event = events[index]
        val next: (Reaction) -> Unit = { reaction ->
            onReact(event.id, reaction)
            dragX = 0f
            index++
        }

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
                    EventVisual(event, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                    SuggestionTag(event.type.title, Modifier.align(Alignment.TopStart).padding(18.dp))
                    Icon(Icons.Default.FavoriteBorder, null, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp))
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text(event.title, fontSize = 31.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text("${event.dateLabel} · ${event.timeLabel}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("${event.city} · ${event.venue}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(event.priceCzk?.let { if (it == 0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionCircle(Icons.Default.Close, MaterialTheme.colorScheme.error) { next(Reaction.HATED) }
                FilledTonalIconButton(onClick = { onOpen(event) }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.Info, "Detail") }
                ActionCircle(Icons.Default.Favorite, MaterialTheme.colorScheme.primary) { next(Reaction.LOVED) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(74.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun DatingFilterPanel(filter: EventFilter, onFilter: (EventFilter) -> Unit) {
    var draft by remember(filter) { mutableStateOf(filter) }
    val cities = listOf("Všechna města", "Ostrava", "Praha", "Brno", "Olomouc", "Opava", "Frýdek-Místek", "Plzeň")
    val price = (draft.maxPrice ?: 3000).coerceIn(0, 5000)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(30.dp),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtry", fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    draft = EventFilter(search = filter.search)
                    onFilter(draft)
                }) { Text("Vymazat vše") }
            }

            Text("Kategorie", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(EventType.ALL, EventType.EVENT, EventType.THEATRE, EventType.CINEMA, EventType.CONCERT, EventType.EXHIBITION).forEach { type ->
                    FilterChip(selected = draft.type == type, onClick = { draft = draft.copy(type = type) }, label = { Text(type.title) })
                }
            }

            Text("Lokalita", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cities.forEach { city -> FilterChip(selected = draft.city == city, onClick = { draft = draft.copy(city = city) }, label = { Text(city) }) }
            }

            Text("Kdy", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kdykoliv", "Dnes", "Zítra", "Tento týden").forEach { date ->
                    FilterChip(selected = draft.date == date, onClick = { draft = draft.copy(date = date) }, label = { Text(date) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Maximální cena", fontWeight = FontWeight.Bold)
                    Text(if (draft.maxPrice == null) "Bez omezení" else "do ${draft.maxPrice} Kč", color = MaterialTheme.colorScheme.primary)
                }
                Switch(checked = draft.freeOnly, onCheckedChange = { draft = draft.copy(freeOnly = it) })
                Spacer(Modifier.width(8.dp))
                Text("Zdarma")
            }
            Slider(
                value = price.toFloat(),
                onValueChange = { draft = draft.copy(maxPrice = it.toInt()) },
                valueRange = 0f..5000f,
                steps = 19
            )

            Button(onClick = { onFilter(draft) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Použít filtry")
            }
        }
    }
}

@Composable
private fun CardListScreen(
    title: String,
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    reactionFor: (String) -> Reaction,
    onReact: (String, Reaction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(title, filter, loading, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { DatingFilterPanel(filter, onFilter) }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nic tu teď není. Zkus změnit filtry.") }
        } else {
            LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(events, key = { it.id }) { event -> EventCard(event, reactionFor(event.id), onReact, onOpen) }
            }
        }
    }
}

@Composable
private fun EventCard(event: CulturalEvent, reaction: Reaction, onReact: (String, Reaction) -> Unit, onOpen: (CulturalEvent) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(event) },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            EventVisual(event, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0050507)))))
            SuggestionTag(event.type.title, Modifier.align(Alignment.TopStart).padding(14.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(event.title, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.venue, fontWeight = FontWeight.SemiBold)
                Text(event.priceCzk?.let { if (it == 0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onReact(event.id, if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) {
                Icon(Icons.Default.ThumbDown, null, tint = if (reaction == Reaction.HATED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { onReact(event.id, if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) {
                Icon(Icons.Default.Favorite, null, tint = if (reaction == Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SuggestionTag(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color(0xAA09080A), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EventVisual(event: CulturalEvent, modifier: Modifier = Modifier) {
    if (!event.imageUrl.isNullOrBlank()) {
        AsyncImage(model = event.imageUrl, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        val symbol = when (event.type) {
            EventType.THEATRE -> "🎭"
            EventType.CINEMA -> "🎬"
            EventType.CONCERT -> "🎵"
            EventType.EXHIBITION -> "🖼️"
            else -> "🎪"
        }
        Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(.45f), Color(0xFF18121A), Color(0xFF050506)))), contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 88.sp)
        }
    }
}

@Composable
private fun EventDetail(event: CulturalEvent, reaction: Reaction, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                EventVisual(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF050506)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(event.type.title.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(event.title, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoRow(Icons.Default.CalendarMonth, "${event.dateLabel} ${event.timeLabel}")
                InfoRow(Icons.Default.LocationOn, "${event.venue}, ${event.city}")
                InfoRow(Icons.Default.Payments, event.priceCzk?.let { if (it == 0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena")
                HorizontalDivider()
                Text("O akci", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ThumbDown, null); Spacer(Modifier.width(8.dp)); Text("Nechci")
                    }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(8.dp)); Text("Miluji")
                    }
                }
                event.ticketUrl?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Vstupenky / detail"); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.OpenInNew, null)
                    }
                }
                Text("Zdroj: ${event.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsScreen(
    apiKey: String,
    onKeyChange: (String) -> Unit,
    viewMode: ViewMode,
    onModeChange: (ViewMode) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var selectedAccent by remember { mutableStateOf(prefs.getString("accent_theme", "wine") ?: "wine") }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 120.dp)) {
        item {
            Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Udělej si Kulturadar po svém", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SettingsCard {
                Text("Vzhled a barvy", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    accents.forEach { accent ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(accent.color)
                                    .then(if (selectedAccent == accent.id) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                    .clickable {
                                        selectedAccent = accent.id
                                        prefs.edit().putString("accent_theme", accent.id).apply()
                                        (context as? Activity)?.recreate()
                                    }
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(accent.label, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            SettingsCard {
                Text("Způsob objevování", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = viewMode == ViewMode.CARDS, onClick = { onModeChange(ViewMode.CARDS) }, label = { Text("Přehled") }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) })
                    FilterChip(selected = viewMode == ViewMode.SWIPE, onClick = { onModeChange(ViewMode.SWIPE) }, label = { Text("Swipe") }, leadingIcon = { Icon(Icons.Default.Swipe, null) })
                }
            }
        }
        item {
            SettingsCard {
                Text("Živá data", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Volitelný Ticketmaster API klíč pro načítání akcí.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = apiKey, onValueChange = onKeyChange, label = { Text("Ticketmaster API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Uložit a načíst")
                }
            }
        }
        item { Text("Kulturadar 1.5 · nový vzhled", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), content = content)
    }
}
