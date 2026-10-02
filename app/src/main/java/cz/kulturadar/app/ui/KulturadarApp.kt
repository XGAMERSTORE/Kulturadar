package cz.kulturadar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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

private enum class Tab(val label: String) { DISCOVER("Objevovat"), LOVED("Milované"), HATED("Nenáviděné"), SETTINGS("Nastavení") }
private enum class ViewMode(val value: String, val title: String, val subtitle: String) {
    CARDS("cards", "Přehled karet", "Více akcí pod sebou, plakáty, ceny a rychlé reakce."),
    SWIPE("swipe", "Swipe režim", "Jedna akce v hlavní roli. Doleva nechci, doprava miluji.")
}

@Composable
fun KulturadarApp() {
    val context = LocalContext.current
    val repo = remember { EventRepository() }
    val store = remember { ReactionStore(context) }
    val settings = remember { context.getSharedPreferences("settings", 0) }

    var viewMode by remember {
        mutableStateOf(settings.getString("view_mode", null)?.let { saved -> ViewMode.values().firstOrNull { it.value == saved } })
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

    if (viewMode == null) {
        ModeChooser { chosen ->
            viewMode = chosen
            settings.edit().putString("view_mode", chosen.value).apply()
        }
        return
    }

    val visible = remember(events, filter, tab, reloadTick) {
        val loved = events.filter { store.get(it.id) == Reaction.LOVED }
        val hated = events.filter { store.get(it.id) == Reaction.HATED }
        val lovedAveragePrice = loved.mapNotNull { it.priceCzk }.takeIf { it.isNotEmpty() }?.average()

        fun recommendationScore(event: CulturalEvent): Int {
            var score = 0
            score += loved.count { it.type == event.type } * 6
            score += loved.count { it.city.equals(event.city, true) } * 4
            score -= hated.count { it.type == event.type } * 4
            score -= hated.count { it.city.equals(event.city, true) } * 2
            if (lovedAveragePrice != null && event.priceCzk != null && abs(event.priceCzk - lovedAveragePrice) <= 250) score += 2
            if (event.priceCzk == 0) score += 1
            if (store.get(event.id) == Reaction.HATED) score -= 100
            return score
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
        }.let { filtered -> if (tab == Tab.DISCOVER) filtered.sortedByDescending(::recommendationScore) else filtered }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .98f)) {
                listOf(Tab.DISCOVER, Tab.LOVED, Tab.HATED, Tab.SETTINGS).forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null },
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
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> EventDetail(
                selected!!,
                store.get(selected!!.id),
                onBack = { selected = null },
                onReact = { r -> store.set(selected!!.id, r); reloadTick++ },
                modifier = Modifier.padding(padding)
            )

            tab == Tab.SETTINGS -> SettingsScreen(
                apiKey = apiKey,
                onKeyChange = { apiKey = it },
                viewMode = viewMode!!,
                onModeChange = { mode ->
                    viewMode = mode
                    settings.edit().putString("view_mode", mode.value).apply()
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

            tab == Tab.DISCOVER && viewMode == ViewMode.SWIPE -> SwipeDiscoverScreen(
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                onReaction = { id, reaction -> store.set(id, reaction); reloadTick++ },
                onFilterToggle = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
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

            else -> DiscoverScreen(
                title = when (tab) {
                    Tab.DISCOVER -> "Kulturadar"
                    Tab.LOVED -> "Milované"
                    Tab.HATED -> "Nenáviděné"
                    else -> "Kulturadar"
                },
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                reactionFor = store::get,
                onReaction = { id, r -> store.set(id, r); reloadTick++ },
                onFilterToggle = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
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
        }
    }
}

@Composable
private fun ModeChooser(onChoose: (ViewMode) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Jak chceš objevovat akce?", fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        Text("Vyber si styl. Později ho můžeš kdykoliv změnit v Nastavení.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))

        ModeChoiceCard(ViewMode.CARDS, Icons.Default.ViewAgenda, onChoose)
        Spacer(Modifier.height(16.dp))
        ModeChoiceCard(ViewMode.SWIPE, Icons.Default.Swipe, onChoose)
    }
}

@Composable
private fun ModeChoiceCard(mode: ViewMode, icon: androidx.compose.ui.graphics.vector.ImageVector, onChoose: (ViewMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onChoose(mode) },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChoose(mode) }, modifier = Modifier.size(58.dp)) { Icon(icon, null) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(mode.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun DiscoverScreen(
    title: String,
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    reactionFor: (String) -> Reaction,
    onReaction: (String, Reaction) -> Unit,
    onFilterToggle: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(title, filter, filterOpen, loading, onRefresh, onFilterToggle, onFilter)
        if (events.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nic tu teď není. Zkus změnit filtry.") }
        else LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(events, key = { it.id }) { event -> EventCard(event, reactionFor(event.id), onReaction, onOpen) }
        }
    }
}

@Composable
private fun SwipeDiscoverScreen(
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    onReaction: (String, Reaction) -> Unit,
    onFilterToggle: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header("Kulturadar", filter, filterOpen, loading, onRefresh, onFilterToggle, onFilter)

        if (events.isEmpty() || index >= events.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Prošel jsi všechny akce 🎉", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { index = 0 }) { Text("Začít znovu") }
                }
            }
            return@Column
        }

        val event = events[index]
        val reactAndNext: (Reaction) -> Unit = { reaction ->
            onReaction(event.id, reaction)
            dragX = 0f
            index++
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        translationX = dragX
                        rotationZ = dragX / 40f
                    }
                    .pointerInput(event.id) {
                        detectDragGestures(
                            onDragEnd = {
                                when {
                                    dragX > 120f -> reactAndNext(Reaction.LOVED)
                                    dragX < -120f -> reactAndNext(Reaction.HATED)
                                    else -> dragX = 0f
                                }
                            }
                        ) { change, amount ->
                            change.consume()
                            dragX += amount.x
                        }
                    }
                    .clickable { onOpen(event) },
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(Modifier.fillMaxSize()) {
                    EventVisual(event, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xE6020604)))))
                    AssistChip(onClick = {}, label = { Text(event.type.title) }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text(event.title, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp)
                        Text(event.venue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(event.priceCzk?.let { if (it == 0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                LargeActionButton(Icons.Default.Close, "Nechci", MaterialTheme.colorScheme.error) { reactAndNext(Reaction.HATED) }
                FilledTonalIconButton(onClick = { onOpen(event) }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.Info, "Detail") }
                LargeActionButton(Icons.Default.Favorite, "Milované", MaterialTheme.colorScheme.primary) { reactAndNext(Reaction.LOVED) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LargeActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun Header(
    title: String,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onFilterToggle: () -> Unit,
    onFilter: (EventFilter) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(18.dp, 16.dp, 18.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Celá ČR · akce · divadla · kina · koncerty", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Obnovit") }
        FilledTonalIconButton(onClick = onFilterToggle) { Icon(Icons.Default.Tune, "Filtry") }
    }
    OutlinedTextField(
        value = filter.search,
        onValueChange = { onFilter(filter.copy(search = it)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("Hledat akci, místo, město…") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        shape = RoundedCornerShape(20.dp)
    )
    Spacer(Modifier.height(10.dp))
    TypeRail(filter.type) { onFilter(filter.copy(type = it)) }
    AnimatedVisibility(filterOpen) { FilterPanel(filter, onFilter) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun TypeRail(selected: EventType, onSelect: (EventType) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EventType.ALL, EventType.EVENT, EventType.THEATRE, EventType.CINEMA, EventType.CONCERT, EventType.EXHIBITION).forEach { t ->
            FilterChip(selected = selected == t, onClick = { onSelect(t) }, label = { Text(t.title) })
        }
    }
}

@Composable
private fun FilterPanel(filter: EventFilter, onFilter: (EventFilter) -> Unit) {
    val cities = listOf("Všechna města", "Praha", "Brno", "Ostrava", "Plzeň", "Olomouc", "Liberec", "Hradec Králové", "Pardubice", "Zlín", "České Budějovice", "Opava", "Frýdek-Místek", "Karlovy Vary", "Jihlava", "Ústí nad Labem", "Tábor", "Mladá Boleslav")
    Surface(Modifier.fillMaxWidth().padding(18.dp, 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filtry", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Město · celá Česká republika")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cities.forEach { city -> FilterChip(selected = filter.city == city, onClick = { onFilter(filter.copy(city = city)) }, label = { Text(city) }) }
            }
            Text("Kdy")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kdykoliv", "Dnes", "Zítra", "Tento týden").forEach { whenText ->
                    FilterChip(selected = filter.date == whenText, onClick = { onFilter(filter.copy(date = whenText)) }, label = { Text(whenText) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(filter.freeOnly, { onFilter(filter.copy(freeOnly = it)) })
                Spacer(Modifier.width(8.dp))
                Text("Jen zdarma")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max. cena")
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = filter.maxPrice?.toString().orEmpty(),
                    onValueChange = { onFilter(filter.copy(maxPrice = it.toIntOrNull())) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = { Text("Kč") },
                    modifier = Modifier.width(150.dp)
                )
            }
        }
    }
}

@Composable
private fun EventCard(event: CulturalEvent, reaction: Reaction, onReaction: (String, Reaction) -> Unit, onOpen: (CulturalEvent) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(event) },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth().height(230.dp)) {
            EventVisual(event, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6020604)))))
            AssistChip(onClick = {}, label = { Text(event.type.title) }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(event.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.venue, fontWeight = FontWeight.SemiBold)
                Text(event.priceCzk?.let { if (it == 0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onReaction(event.id, if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) {
                Icon(Icons.Default.ThumbDown, "Nenáviděné", tint = if (reaction == Reaction.HATED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { onReaction(event.id, if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) {
                Icon(Icons.Default.Favorite, "Milované", tint = if (reaction == Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
        Box(
            modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(.5f), Color(0xFF0A2518), Color(0xFF020604)))),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, fontSize = 92.sp)
        }
    }
}

@Composable
private fun EventDetail(event: CulturalEvent, reaction: Reaction, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                EventVisual(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF020604)))))
                IconButton(onClick = onBack, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
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
                Text("O akci", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(event.description, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ThumbDown, null); Spacer(Modifier.width(8.dp)); Text("Nenáviděné")
                    }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(8.dp)); Text("Milované")
                    }
                }
                event.ticketUrl?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Vstupenky / detail pořadatele"); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.OpenInNew, null)
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
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Text("Nastavení", fontSize = 30.sp, fontWeight = FontWeight.Black) }
        item {
            Text("Způsob objevování", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = viewMode == ViewMode.CARDS, onClick = { onModeChange(ViewMode.CARDS) }, label = { Text("Přehled karet") }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) })
                FilterChip(selected = viewMode == ViewMode.SWIPE, onClick = { onModeChange(ViewMode.SWIPE) }, label = { Text("Swipe") }, leadingIcon = { Icon(Icons.Default.Swipe, null) })
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Živá data", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Ticketmaster je volitelný zdroj pro obecné akce po celé České republice. Bez klíče běží aplikace na rozšířených ukázkových datech z celé ČR.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(value = apiKey, onValueChange = onKeyChange, label = { Text("Ticketmaster API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Uložit a načíst živá data")
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Kulturadar 1.1", fontWeight = FontWeight.Bold)
            Text("Oba režimy používají stejné Milované/Nenáviděné, filtry a doporučení. Režim lze kdykoliv změnit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
