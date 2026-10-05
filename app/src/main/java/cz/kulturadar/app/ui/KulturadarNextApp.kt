package cz.kulturadar.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cz.kulturadar.app.BuildConfig
import cz.kulturadar.app.data.EventRepository
import cz.kulturadar.app.data.ReactionStore
import cz.kulturadar.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private enum class NextTab(val title: String) {
    DISCOVER("Objevovat"), SAVED("Uložené"), HIDDEN("Skryté"), SETTINGS("Nastavení")
}

private enum class NextMode(val key: String, val title: String) { CARDS("cards", "Přehled"), SWIPE("swipe", "Swipe") }

@Composable
fun KulturadarNextApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val repo = remember { EventRepository() }
    val reactions = remember { ReactionStore(context) }
    val premium = BuildConfig.INTERNAL_PREMIUM

    var tab by remember { mutableStateOf(NextTab.DISCOVER) }
    var mode by remember {
        mutableStateOf(
            if (prefs.getString("view_mode", "swipe") == "cards") NextMode.CARDS else NextMode.SWIPE
        )
    }
    var filter by remember { mutableStateOf(EventFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(prefs.getString("tm_key", "") ?: "") }
    var events by remember { mutableStateOf<List<CulturalEvent>>(emptyList()) }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reactionTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (apiKey.isBlank()) {
            events = emptyList()
            error = "Pro reálné akce vlož v Nastavení Ticketmaster API klíč. Kulturadar už nezobrazuje falešné ukázkové akce."
            return
        }
        loading = true
        error = null
        scope.launch {
            val live = withContext(Dispatchers.IO) { repo.ticketmaster(apiKey, filter.city, filter.search) }
            events = live
            if (live.isEmpty()) error = "Nenašel jsem žádné reálné akce pro tento výběr. Zkus jiné město nebo filtry."
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (apiKey.isNotBlank()) refresh()
    }

    BackHandler(enabled = selected != null || filterOpen || tab != NextTab.DISCOVER) {
        when {
            selected != null -> selected = null
            filterOpen -> filterOpen = false
            tab != NextTab.DISCOVER -> tab = NextTab.DISCOVER
        }
    }

    val visible = remember(events, filter, tab, reactionTick, premium) {
        val loved = events.filter { reactions.get(it.id) == Reaction.LOVED }
        val disliked = events.filter { reactions.get(it.id) == Reaction.HATED }
        val avgPrice = loved.mapNotNull { it.priceCzk }.takeIf { it.isNotEmpty() }?.average()

        fun smartScore(e: CulturalEvent): Int {
            if (!premium) return 0
            var score = 0
            score += loved.count { it.type == e.type } * 8
            score += loved.count { it.city.equals(e.city, true) } * 5
            score += loved.count { it.genre != null && it.genre.equals(e.genre, true) } * 6
            score -= disliked.count { it.type == e.type } * 5
            if (avgPrice != null && e.priceCzk != null && abs(e.priceCzk - avgPrice) <= 300) score += 3
            if (!e.imageIsFallback && !e.imageUrl.isNullOrBlank()) score += 2
            return score
        }

        events.filter { e ->
            val reaction = reactions.get(e.id)
            val tabOk = when (tab) {
                NextTab.SAVED -> reaction == Reaction.LOVED
                NextTab.HIDDEN -> reaction == Reaction.HATED
                else -> true
            }
            val hiddenOk = tab != NextTab.DISCOVER || !filter.hideDisliked || reaction != Reaction.HATED
            val typeOk = filter.type == EventType.ALL || e.type == filter.type
            val cityOk = filter.city == "Všechna města" || e.city.equals(filter.city, true)
            val freeOk = !filter.freeOnly || e.priceCzk == 0
            val priceOk = filter.maxPrice == null || (e.priceCzk ?: Int.MAX_VALUE) <= filter.maxPrice!!
            val imageOk = !filter.onlyWithRealImage || (!e.imageUrl.isNullOrBlank() && !e.imageIsFallback)
            val query = filter.search.trim()
            val searchOk = query.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city, e.genre.orEmpty()).any { it.contains(query, true) }
            val dateOk = dateMatches(e.dateLabel, filter.date)
            tabOk && hiddenOk && typeOk && cityOk && freeOk && priceOk && imageOk && searchOk && dateOk
        }.let { list -> if (tab == NextTab.DISCOVER) list.sortedByDescending(::smartScore) else list }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                NextTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null; filterOpen = false },
                        icon = {
                            Icon(
                                when (item) {
                                    NextTab.DISCOVER -> Icons.Default.Explore
                                    NextTab.SAVED -> Icons.Default.Favorite
                                    NextTab.HIDDEN -> Icons.Default.VisibilityOff
                                    NextTab.SETTINGS -> Icons.Default.Settings
                                }, null
                            )
                        },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> NextEventDetail(
                event = selected!!,
                reaction = reactions.get(selected!!.id),
                onBack = { selected = null },
                onReact = { reactions.set(selected!!.id, it); reactionTick++ },
                modifier = Modifier.padding(padding)
            )
            tab == NextTab.SETTINGS -> NextSettings(
                apiKey = apiKey,
                onApiKey = { apiKey = it },
                mode = mode,
                onMode = {
                    mode = it
                    prefs.edit().putString("view_mode", it.key).apply()
                },
                premium = premium,
                onSave = {
                    prefs.edit().putString("tm_key", apiKey.trim()).apply()
                    refresh()
                },
                modifier = Modifier.padding(padding)
            )
            tab == NextTab.DISCOVER && mode == NextMode.SWIPE -> NextSwipe(
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                error = error,
                premium = premium,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
                onReact = { id, r -> reactions.set(id, r); reactionTick++ },
                onRefresh = ::refresh,
                modifier = Modifier.padding(padding)
            )
            else -> NextList(
                title = tab.title,
                events = visible,
                filter = filter,
                filterOpen = filterOpen,
                loading = loading,
                error = error,
                premium = premium,
                reactionFor = reactions::get,
                onFilterOpen = { filterOpen = !filterOpen },
                onFilter = { filter = it },
                onOpen = { selected = it },
                onReact = { id, r -> reactions.set(id, r); reactionTick++ },
                onRefresh = ::refresh,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun NextHeader(
    title: String,
    filter: EventFilter,
    loading: Boolean,
    premium: Boolean,
    onRefresh: () -> Unit,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    if (premium) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text("PREMIUM") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
                    }
                }
                Text("Jen reálné akce · žádné fake karty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Obnovit") }
            FilledTonalIconButton(onClick = onFilterOpen) { Icon(Icons.Default.Tune, "Filtry") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = filter.search,
            onValueChange = { onFilter(filter.copy(search = it)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Akce, interpret, místo, město…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EventType.entries.forEach { type ->
                FilterChip(selected = filter.type == type, onClick = { onFilter(filter.copy(type = type)) }, label = { Text(type.title) })
            }
        }
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NextFilterPanel(filter: EventFilter, premium: Boolean, onFilter: (EventFilter) -> Unit) {
    var draft by remember(filter) { mutableStateOf(filter) }
    val cities = listOf("Všechna města", "Ostrava", "Praha", "Brno", "Olomouc", "Opava", "Plzeň", "Pardubice", "Zlín")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(26.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtry", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = EventFilter(search = filter.search); onFilter(draft) }) { Text("Reset") }
            }
            Text("Město", fontWeight = FontWeight.Bold)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.onlyWithRealImage, { draft = draft.copy(onlyWithRealImage = it) })
                    Spacer(Modifier.width(8.dp)); Text("Jen akce s reálným obrázkem")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.hideDisliked, { draft = draft.copy(hideDisliked = it) })
                    Spacer(Modifier.width(8.dp)); Text("Automaticky skrývat Nechci")
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
            Button(onClick = { onFilter(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Použít") }
        }
    }
}

@Composable
private fun NextSwipe(
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    error: String?,
    premium: Boolean,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onReact: (String, Reaction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    Column(modifier.fillMaxSize()) {
        NextHeader("Kulturadar", filter, loading, premium, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { NextFilterPanel(filter, premium, onFilter) }
        if (events.isEmpty() || index >= events.size) {
            NextEmpty(error ?: "Prošel jsi všechny nalezené akce.", onRefresh, Modifier.fillMaxSize())
            return@Column
        }
        val event = events[index]
        fun next(r: Reaction) { onReact(event.id, r); dragX = 0f; index++ }
        Column(Modifier.fillMaxSize().padding(18.dp, 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(
                Modifier.fillMaxWidth().weight(1f)
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
                shape = RoundedCornerShape(30.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    NextVisual(event, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                    AssistChip(onClick = {}, label = { Text(event.type.title) }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text(event.title, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${event.dateLabel} ${event.timeLabel}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("${event.city} · ${event.venue}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(event.priceCzk?.let { "od $it Kč" } ?: "Cena neuvedena")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FilledTonalIconButton(onClick = { next(Reaction.HATED) }, modifier = Modifier.size(70.dp)) { Icon(Icons.Default.Close, "Nechci", tint = MaterialTheme.colorScheme.error) }
                FilledTonalIconButton(onClick = { onOpen(event) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Info, "Detail") }
                FilledTonalIconButton(onClick = { next(Reaction.LOVED) }, modifier = Modifier.size(70.dp)) { Icon(Icons.Default.Favorite, "Uložit", tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NextList(
    title: String,
    events: List<CulturalEvent>,
    filter: EventFilter,
    filterOpen: Boolean,
    loading: Boolean,
    error: String?,
    premium: Boolean,
    reactionFor: (String) -> Reaction,
    onFilterOpen: () -> Unit,
    onFilter: (EventFilter) -> Unit,
    onOpen: (CulturalEvent) -> Unit,
    onReact: (String, Reaction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        NextHeader(title, filter, loading, premium, onRefresh, onFilterOpen, onFilter)
        AnimatedVisibility(filterOpen) { NextFilterPanel(filter, premium, onFilter) }
        if (events.isEmpty()) {
            NextEmpty(error ?: "Nic tu zatím není.", onRefresh, Modifier.fillMaxSize())
        } else {
            LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(events, key = { it.id }) { event ->
                    NextCard(event, reactionFor(event.id), onReact, onOpen)
                }
            }
        }
    }
}

@Composable
private fun NextCard(event: CulturalEvent, reaction: Reaction, onReact: (String, Reaction) -> Unit, onOpen: (CulturalEvent) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(event) }, shape = RoundedCornerShape(26.dp)) {
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            NextVisual(event, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xED050507)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(event.title, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.venue, fontWeight = FontWeight.Bold)
                Text(event.genre ?: event.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun NextVisual(event: CulturalEvent, modifier: Modifier = Modifier) {
    if (!event.imageUrl.isNullOrBlank()) {
        AsyncImage(model = event.imageUrl, contentDescription = event.title, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface, Color.Black)))) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ImageNotSupported, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Pořadatel neposkytl obrázek", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NextEventDetail(event: CulturalEvent, reaction: Reaction, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(350.dp)) {
                NextVisual(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5050507)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(event.subtitle.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(event.title, fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NextInfo(Icons.Default.CalendarMonth, "${event.dateLabel} ${event.timeLabel}")
                NextInfo(Icons.Default.LocationOn, "${event.venue}, ${event.city}")
                NextInfo(Icons.Default.Payments, event.priceCzk?.let { "od $it Kč" } ?: "Cena neuvedena")
                event.status?.let { NextInfo(Icons.Default.Info, "Stav: $it") }
                HorizontalDivider()
                Text("O akci", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.VisibilityOff, null); Spacer(Modifier.width(6.dp)); Text("Nechci")
                    }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(6.dp)); Text("Uložit")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { shareEvent(context, event) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Sdílet") }
                    OutlinedButton(onClick = { addToCalendar(context, event) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Event, null); Spacer(Modifier.width(6.dp)); Text("Kalendář") }
                }
                if (event.latitude != null && event.longitude != null) {
                    OutlinedButton(onClick = { openMap(context, event) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(8.dp)); Text("Otevřít mapu") }
                }
                event.ticketUrl?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Vstupenky / oficiální detail"); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.OpenInNew, null)
                    }
                }
                event.imageAttribution?.let { Text("Foto: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Zdroj: ${event.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NextSettings(
    apiKey: String,
    onApiKey: (String) -> Unit,
    mode: NextMode,
    onMode: (NextMode) -> Unit,
    premium: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(if (premium) "Premium aktivní" else "Premium", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Smart Mix podle lajků, pokročilé filtry, jen skutečné obrázky, automatické skrývání Nechci, cenový limit, mapa, kalendář a sdílení.")
                    if (premium) Text("Interní Premium build", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Text("Způsob objevování", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NextMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { onMode(item) }, label = { Text(item.title) }) }
            }
        }
        item {
            Text("Zdroj reálných akcí", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Ticketmaster Discovery API. Klíč se ukládá jen lokálně v telefonu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(apiKey, onApiKey, label = { Text("Ticketmaster API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Uložit a načíst reálné akce") }
        }
        item {
            Text("Co se změnilo", fontWeight = FontWeight.Bold)
            Text("Odstraněny zastaralé ručně vložené GoOut karty a emoji náhražky. Pokud není reálný zdroj dostupný, aplikace to řekne místo vymyšleného obsahu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("Kulturadar 1.6", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun NextEmpty(text: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Radar, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Zkusit znovu") }
        }
    }
}

@Composable
private fun NextInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

private fun dateMatches(dateLabel: String, filter: String): Boolean {
    if (filter == "Kdykoliv") return true
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val today = Calendar.getInstance()
    val target = when (filter) {
        "Dnes" -> fmt.format(today.time)
        "Zítra" -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.let { fmt.format(it.time) }
        "Tento týden" -> {
            val parsed = runCatching { fmt.parse(dateLabel) }.getOrNull() ?: return false
            val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
            parsed.time in today.timeInMillis..end.timeInMillis
        }
        else -> return true
    }
    return dateLabel == target
}

private fun shareEvent(context: android.content.Context, event: CulturalEvent) {
    val text = buildString {
        append(event.title).append("\n")
        append(event.dateLabel).append(" ").append(event.timeLabel).append(" · ").append(event.venue).append(", ").append(event.city)
        event.ticketUrl?.let { append("\n").append(it) }
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Sdílet akci"))
}

private fun addToCalendar(context: android.content.Context, event: CulturalEvent) {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, "${event.venue}, ${event.city}")
        .putExtra(CalendarContract.Events.DESCRIPTION, event.ticketUrl ?: event.description)
    context.startActivity(intent)
}

private fun openMap(context: android.content.Context, event: CulturalEvent) {
    val lat = event.latitude ?: return
    val lon = event.longitude ?: return
    val label = Uri.encode(event.venue)
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
}
