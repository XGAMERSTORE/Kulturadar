package cz.kulturadar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

@Composable
fun KulturadarApp() {
    val context = LocalContext.current
    val repo = remember { EventRepository() }
    val store = remember { ReactionStore(context) }
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var filter by remember { mutableStateOf(EventFilter()) }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(context.getSharedPreferences("settings", 0).getString("tm_key", "") ?: "") }
    var events by remember { mutableStateOf(repo.demoEvents()) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

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
            val tabOk = when (tab) { Tab.LOVED -> reaction == Reaction.LOVED; Tab.HATED -> reaction == Reaction.HATED; else -> true }
            val typeOk = filter.type == EventType.ALL || e.type == filter.type
            val cityOk = filter.city == "Všechna města" || e.city.equals(filter.city, true)
            val freeOk = !filter.freeOnly || e.priceCzk == 0
            val dateOk = when (filter.date) { "Dnes" -> e.dateLabel.equals("Dnes", true); "Zítra" -> e.dateLabel.equals("Zítra", true); else -> true }
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
                        icon = { Icon(when(item){Tab.DISCOVER->Icons.Default.Explore;Tab.LOVED->Icons.Default.Favorite;Tab.HATED->Icons.Default.ThumbDown;Tab.SETTINGS->Icons.Default.Settings}, null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> EventDetail(selected!!, store.get(selected!!.id), onBack = { selected = null }, onReact = { r -> store.set(selected!!.id, r); reloadTick++ }, modifier = Modifier.padding(padding))
            tab == Tab.SETTINGS -> SettingsScreen(apiKey, onKeyChange = { apiKey = it }, onSave = {
                context.getSharedPreferences("settings", 0).edit().putString("tm_key", apiKey).apply()
                loading = true
                scope.launch {
                    val live = withContext(Dispatchers.IO) { repo.ticketmaster(apiKey, filter.city, filter.search) }
                    if (live.isNotEmpty()) events = live
                    loading = false
                }
            }, modifier = Modifier.padding(padding))
            else -> DiscoverScreen(
                title = when(tab){Tab.DISCOVER->"Kulturadar";Tab.LOVED->"Milované";Tab.HATED->"Nenáviděné";else->"Kulturadar"},
                deckMode = tab == Tab.DISCOVER,
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
private fun DiscoverScreen(
    title: String, deckMode: Boolean, events: List<CulturalEvent>, filter: EventFilter, filterOpen: Boolean, loading: Boolean,
    reactionFor: (String)->Reaction, onReaction: (String, Reaction)->Unit, onFilterToggle: ()->Unit,
    onFilter: (EventFilter)->Unit, onOpen: (CulturalEvent)->Unit, onRefresh: ()->Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp, 18.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black)
                if (deckMode) Text("Objevuj akce po celé ČR", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Obnovit") }
            FilledTonalIconButton(onClick = onFilterToggle) { Icon(Icons.Default.Tune, "Filtry") }
        }
        if (!deckMode || filterOpen) {
            OutlinedTextField(
                value = filter.search, onValueChange = { onFilter(filter.copy(search = it)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Hledat akci, místo, město…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.height(8.dp))
            TypeRail(filter.type) { onFilter(filter.copy(type = it)) }
        }
        AnimatedVisibility(filterOpen) { FilterPanel(filter, onFilter) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (events.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nic tu teď není. Zkus změnit filtry.") }
        else if (deckMode) EventDeck(events, reactionFor, onReaction, onOpen)
        else LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(events, key = { it.id }) { event -> EventListCard(event, reactionFor(event.id), onReaction, onOpen) }
        }
    }
}

@Composable
private fun EventDeck(events: List<CulturalEvent>, reactionFor: (String)->Reaction, onReaction: (String, Reaction)->Unit, onOpen: (CulturalEvent)->Unit) {
    var index by remember(events.map { it.id }) { mutableIntStateOf(0) }
    if (index >= events.size) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Projel jsi všechny akce 👀", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Změň filtry nebo obnov nabídku.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val event = events[index]
    var dragX by remember(event.id) { mutableFloatStateOf(0f) }
    val threshold = 180f
    fun decide(reaction: Reaction) { onReaction(event.id, reaction); index++; dragX = 0f }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EventHeroCard(
                event = event,
                reaction = reactionFor(event.id),
                onOpen = { onOpen(event) },
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = dragX; rotationZ = dragX / 55f }.pointerInput(event.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> dragX += amount },
                        onDragEnd = {
                            when { dragX > threshold -> decide(Reaction.LOVED); dragX < -threshold -> decide(Reaction.HATED); else -> dragX = 0f }
                        }
                    )
                }
            )
            if (dragX > 50f) Text("MILOVANÉ", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopStart).padding(28.dp))
            else if (dragX < -50f) Text("NECHCI", color = MaterialTheme.colorScheme.error, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(28.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = { decide(Reaction.HATED) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, "Nechci", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(34.dp)) }
            }
            Surface(onClick = { onOpen(event) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Info, "Detail", modifier = Modifier.size(26.dp)) }
            }
            Surface(onClick = { decide(Reaction.LOVED) }, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, "Milované", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
            }
        }
        Text("Přejeď doleva / doprava nebo použij tlačítka", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun EventHeroCard(event: CulturalEvent, reaction: Reaction, onOpen: ()->Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onOpen), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxSize()) {
            EventArtwork(event, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xAA020604), Color(0xFF020604)))))
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .48f), modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text(event.type.title.uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            if (reaction != Reaction.NONE) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(if (reaction == Reaction.LOVED) Icons.Default.Favorite else Icons.Default.ThumbDown, null, tint = if (reaction == Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp)) {
                Text(event.title, fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(event.subtitle, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text("${event.dateLabel} ${event.timeLabel}", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text("${event.venue} · ${event.city}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(10.dp))
                Text(event.priceCzk?.let { if (it == 0) "ZDARMA" else "OD $it Kč" } ?: "CENA NEUVEDENA", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun EventArtwork(event: CulturalEvent, modifier: Modifier = Modifier) {
    if (!event.imageUrl.isNullOrBlank()) {
        AsyncImage(model = event.imageUrl, contentDescription = event.title, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        val emoji = when (event.type) { EventType.THEATRE -> "🎭"; EventType.CINEMA -> "🎬"; EventType.CONCERT -> "🎵"; EventType.EXHIBITION -> "🖼️"; else -> "🎪" }
        Box(modifier.background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .55f), Color(0xFF082417), Color(0xFF010503)))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(emoji, fontSize = 104.sp); Text(event.type.title.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = .88f)) }
        }
    }
}

@Composable
private fun TypeRail(selected: EventType, onSelect: (EventType)->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EventType.ALL, EventType.EVENT, EventType.THEATRE, EventType.CINEMA, EventType.CONCERT, EventType.EXHIBITION).forEach { t -> FilterChip(selected = selected == t, onClick = { onSelect(t) }, label = { Text(t.title) }) }
    }
}

@Composable
private fun FilterPanel(filter: EventFilter, onFilter: (EventFilter)->Unit) {
    val cities = listOf("Všechna města", "Praha", "Brno", "Ostrava", "Plzeň", "Olomouc", "Liberec", "Hradec Králové", "Pardubice", "Zlín", "České Budějovice", "Opava", "Frýdek-Místek", "Karlovy Vary", "Jihlava", "Ústí nad Labem", "Tábor", "Mladá Boleslav")
    Surface(Modifier.fillMaxWidth().padding(18.dp, 8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.6f), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filtry", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Město · celá Česká republika")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { cities.forEach { city -> FilterChip(selected=filter.city==city,onClick={onFilter(filter.copy(city=city))},label={Text(city)}) } }
            Text("Kdy")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Kdykoliv","Dnes","Zítra","Tento týden").forEach { whenText -> FilterChip(selected=filter.date==whenText,onClick={onFilter(filter.copy(date=whenText))},label={Text(whenText)}) } }
            TypeRail(filter.type) { onFilter(filter.copy(type = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(filter.freeOnly, { onFilter(filter.copy(freeOnly=it)) }); Spacer(Modifier.width(8.dp)); Text("Jen zdarma") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max. cena"); Spacer(Modifier.width(12.dp))
                OutlinedTextField(value = filter.maxPrice?.toString().orEmpty(), onValueChange = { onFilter(filter.copy(maxPrice = it.toIntOrNull())) }, keyboardOptions = KeyboardOptions(keyboardType=KeyboardType.Number), singleLine=true, suffix={Text("Kč")}, modifier=Modifier.width(150.dp))
            }
        }
    }
}

@Composable
private fun EventListCard(event: CulturalEvent, reaction: Reaction, onReaction: (String, Reaction)->Unit, onOpen: (CulturalEvent)->Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(event) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            EventArtwork(event, Modifier.width(125.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)))
            Column(Modifier.weight(1f).padding(14.dp)) {
                Text(event.type.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(event.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.priceCzk?.let { if(it==0) "Zdarma" else "od $it Kč" } ?: "Cena neuvedena", modifier = Modifier.weight(1f))
                    IconButton(onClick={ onReaction(event.id, if(reaction==Reaction.HATED) Reaction.NONE else Reaction.HATED) }) { Icon(Icons.Default.ThumbDown, null, tint=if(reaction==Reaction.HATED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick={ onReaction(event.id, if(reaction==Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) { Icon(Icons.Default.Favorite, null, tint=if(reaction==Reaction.LOVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun EventDetail(event: CulturalEvent, reaction: Reaction, onBack: ()->Unit, onReact: (Reaction)->Unit, modifier: Modifier=Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding=PaddingValues(bottom=120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                EventArtwork(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xE6020604)))))
                IconButton(onClick=onBack,modifier=Modifier.padding(12.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack,"Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) { Text(event.type.title.uppercase(),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold); Text(event.title,fontSize=34.sp,fontWeight=FontWeight.Black) }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                InfoRow(Icons.Default.CalendarMonth,"${event.dateLabel} ${event.timeLabel}")
                InfoRow(Icons.Default.LocationOn,"${event.venue}, ${event.city}")
                InfoRow(Icons.Default.Payments,event.priceCzk?.let{if(it==0)"Zdarma" else "od $it Kč"}?:"Cena neuvedena")
                HorizontalDivider()
                Text("O akci",fontSize=21.sp,fontWeight=FontWeight.Bold)
                Text(event.description,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={onReact(if(reaction==Reaction.HATED)Reaction.NONE else Reaction.HATED)},modifier=Modifier.weight(1f)){Icon(Icons.Default.ThumbDown,null);Spacer(Modifier.width(8.dp));Text("Nechci")}
                    Button(onClick={onReact(if(reaction==Reaction.LOVED)Reaction.NONE else Reaction.LOVED)},modifier=Modifier.weight(1f)){Icon(Icons.Default.Favorite,null);Spacer(Modifier.width(8.dp));Text("Milované")}
                }
                event.ticketUrl?.let { url -> Button(onClick={context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))},modifier=Modifier.fillMaxWidth()){Text("Vstupenky / detail pořadatele");Spacer(Modifier.width(8.dp));Icon(Icons.Default.OpenInNew,null)} }
                Text("Zdroj: ${event.source}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector,text:String){Row(verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text(text,fontWeight=FontWeight.SemiBold)}}

@Composable
private fun SettingsScreen(apiKey:String,onKeyChange:(String)->Unit,onSave:()->Unit,modifier:Modifier=Modifier){
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement=Arrangement.spacedBy(18.dp)){
        Spacer(Modifier.height(4.dp));Text("Nastavení",fontSize=30.sp,fontWeight=FontWeight.Black)
        Text("Živá data",fontSize=20.sp,fontWeight=FontWeight.Bold)
        Text("Ticketmaster je volitelný zdroj pro obecné akce. Bez klíče aplikace používá vestavěné ukázkové akce z celé ČR.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value=apiKey,onValueChange=onKeyChange,label={Text("Ticketmaster API key")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(onClick=onSave,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Sync,null);Spacer(Modifier.width(8.dp));Text("Uložit a načíst živá data")}
        HorizontalDivider()
        Text("Kulturadar 1.1",fontWeight=FontWeight.Bold)
        Text("Objevování akcí funguje jako rychlý deck: akci dáš do Milovaných nebo ji odmítneš. Žádné profily lidí — jen kultura.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
