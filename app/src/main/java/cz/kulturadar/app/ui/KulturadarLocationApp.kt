package cz.kulturadar.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cz.kulturadar.app.data.CzechPlace
import cz.kulturadar.app.data.EventCache
import cz.kulturadar.app.data.EventRepository
import cz.kulturadar.app.data.PlaceRepository
import cz.kulturadar.app.data.ReactionStore
import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.Reaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class UiMode(val key: String, val label: String) { SWIPE("swipe", "Swipe"), LIST("cards", "Seznam") }
private enum class AppTab(val label: String) { DISCOVER("Objevovat"), SAVED("Uložené"), SETTINGS("Nastavení") }

@Composable
fun KulturadarLocationApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var ready by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
    if (!ready) {
        SetupScreen { place, radius, mode ->
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
    MainScreen(onResetPlace = {
        prefs.edit().putBoolean("onboarding_done", false).apply()
        ready = false
    })
}

@Composable
private fun SetupScreen(onDone: (CzechPlace, Int, UiMode) -> Unit) {
    val repo = remember { PlaceRepository() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CzechPlace>>(emptyList()) }
    var selected by remember { mutableStateOf<CzechPlace?>(null) }
    var radius by remember { mutableIntStateOf(30) }
    var mode by remember { mutableStateOf(UiMode.SWIPE) }
    var loading by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF030705)) {
        LazyColumn(contentPadding = PaddingValues(24.dp, 40.dp, 24.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFF123C24), modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Radar, null, tint = Color(0xFF61F59A), modifier = Modifier.size(32.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kulturadar", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Text("Najdi akce kolem sebe", color = Color(0xFF9EB7A7))
                    }
                }
            }
            item {
                Text("Zobrazení na úvodu", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UiMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }, leadingIcon = { Icon(if (m == UiMode.SWIPE) Icons.Default.Swipe else Icons.Default.ViewAgenda, null) })
                    }
                }
            }
            item {
                Text("Město nebo vesnice", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Vyhledávání funguje pro obce v celém Česku.", color = Color(0xFF9EB7A7))
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
                                    results = withContext(Dispatchers.IO) { repo.search(query) }
                                    loading = false
                                }
                            }
                        }) { Icon(Icons.Default.Search, "Vyhledat") }
                    },
                    placeholder = { Text("Např. Rychvald, Horní Lhota…") },
                    shape = RoundedCornerShape(22.dp)
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            items(results, key = { "${it.name}-${it.latitude}-${it.longitude}" }) { p ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selected = p },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected == p) Color(0xFF123C24) else Color(0xFF0B120E)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = Color(0xFF61F59A))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontWeight = FontWeight.Bold)
                            if (p.detail.isNotBlank()) Text(p.detail, color = Color(0xFF9EB7A7), fontSize = 12.sp)
                        }
                        if (selected == p) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF61F59A))
                    }
                }
            }
            item {
                Text("Vzdálenost", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Hledat do $radius km", color = Color(0xFF61F59A), fontWeight = FontWeight.Bold)
                Slider(value = radius.toFloat(), onValueChange = { radius = it.roundToInt() }, valueRange = 5f..200f, steps = 38)
            }
            item {
                Button(onClick = { selected?.let { onDone(it, radius, mode) } }, enabled = selected != null, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Default.Explore, null); Spacer(Modifier.width(8.dp)); Text("Začít objevovat")
                }
            }
        }
    }
}

@Composable
private fun MainScreen(onResetPlace: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val repo = remember { EventRepository() }
    val cache = remember { EventCache(context) }
    val reactions = remember { ReactionStore(context) }
    val scope = rememberCoroutineScope()

    val placeName = prefs.getString("home_place", "Ostrava") ?: "Ostrava"
    val placeDetail = prefs.getString("home_place_detail", "") ?: ""
    val placeLat = java.lang.Double.longBitsToDouble(prefs.getLong("home_lat_bits", java.lang.Double.doubleToRawLongBits(49.8209)))
    val placeLon = java.lang.Double.longBitsToDouble(prefs.getLong("home_lon_bits", java.lang.Double.doubleToRawLongBits(18.2625)))

    var radius by remember { mutableIntStateOf(prefs.getInt("radius_km", 30)) }
    var mode by remember { mutableStateOf(if (prefs.getString("view_mode", "swipe") == "cards") UiMode.LIST else UiMode.SWIPE) }
    var tab by remember { mutableStateOf(AppTab.DISCOVER) }
    var events by remember { mutableStateOf(cache.load().ifEmpty { repo.demoEvents() }) }
    var search by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(prefs.getString("tm_key", "") ?: "") }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    fun distanceOk(e: CulturalEvent): Boolean {
        val lat = e.latitude
        val lon = e.longitude
        return if (lat != null && lon != null) PlaceRepository.distanceKm(placeLat, placeLon, lat, lon) <= radius else e.city.equals(placeName, true)
    }

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { repo.loadAllSources(apiKey.trim(), "Všechna města", search) }
            val nearby = result.events.filter(::distanceOk)
            if (nearby.isNotEmpty()) {
                events = nearby
                withContext(Dispatchers.IO) { cache.save(nearby) }
            }
            message = if (nearby.isEmpty()) "V okruhu $radius km od $placeName teď nic není. Zkus větší vzdálenost." else result.error
            loading = false
        }
    }

    LaunchedEffect(placeName, radius) { refresh() }
    BackHandler(enabled = selected != null || tab != AppTab.DISCOVER) { if (selected != null) selected = null else tab = AppTab.DISCOVER }

    val visible = remember(events, search, tab, tick) {
        events.filter { e ->
            val r = reactions.get(e.id)
            val tabOk = tab != AppTab.SAVED || r == Reaction.LOVED
            val q = search.trim()
            val qOk = q.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city, e.genre.orEmpty()).any { it.contains(q, true) }
            tabOk && qOk && r != Reaction.HATED
        }.sortedBy { it.dateLabel + it.timeLabel }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF2050907)) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null },
                        icon = { Icon(when (item) { AppTab.DISCOVER -> Icons.Default.Explore; AppTab.SAVED -> Icons.Default.Favorite; AppTab.SETTINGS -> Icons.Default.Settings }, null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> DetailScreen(selected!!, reactions.get(selected!!.id), onBack = { selected = null }, onReact = { reactions.set(selected!!.id, it); tick++ }, modifier = Modifier.padding(padding))
            tab == AppTab.SETTINGS -> SettingsScreenV2(
                placeName = placeName,
                placeDetail = placeDetail,
                radius = radius,
                onRadius = { radius = it; prefs.edit().putInt("radius_km", it).apply() },
                mode = mode,
                onMode = { mode = it; prefs.edit().putString("view_mode", it.key).apply() },
                apiKey = apiKey,
                onApiKey = { apiKey = it },
                onSaveApi = { prefs.edit().putString("tm_key", apiKey.trim()).apply(); refresh() },
                onResetPlace = onResetPlace,
                modifier = Modifier.padding(padding)
            )
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                Header(placeName, radius, search, { search = it }, loading, ::refresh)
                message?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                if (mode == UiMode.SWIPE && tab == AppTab.DISCOVER) {
                    SwipeScreenV2(events = visible, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); tick++ }, modifier = Modifier.weight(1f))
                } else {
                    ListScreenV2(events = visible, reactions = reactions, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); tick++ }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Header(place: String, radius: Int, search: String, onSearch: (String) -> Unit, loading: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kulturadar", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("$place · do $radius km", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Obnovit") }
        }
        OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Hledat akci, interpreta, místo…") }, shape = RoundedCornerShape(24.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun SwipeScreenV2(events: List<CulturalEvent>, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    if (events.isEmpty() || index >= events.size) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (events.isEmpty()) "Žádné akce v okolí." else "Prošel jsi všechny akce.") }
        return
    }
    val e = events[index]
    fun next(r: Reaction) { onReact(e.id, r); dragX = 0f; index++ }
    Column(modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .graphicsLayer { translationX = dragX; rotationZ = dragX / 45f }
                .pointerInput(e.id) { detectDragGestures(onDragEnd = { when { dragX > 120f -> next(Reaction.LOVED); dragX < -120f -> next(Reaction.HATED); else -> dragX = 0f } }) { change, amount -> change.consume(); dragX += amount.x } }
                .clickable { onOpen(e) },
            shape = RoundedCornerShape(30.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                EventImage(e, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(e.title, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${e.dateLabel} ${e.timeLabel}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("${e.city} · ${e.venue}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            FilledTonalIconButton(onClick = { next(Reaction.HATED) }, modifier = Modifier.size(70.dp)) { Icon(Icons.Default.Close, "Nechci", tint = MaterialTheme.colorScheme.error) }
            FilledTonalIconButton(onClick = { onOpen(e) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Info, "Detail") }
            FilledTonalIconButton(onClick = { next(Reaction.LOVED) }, modifier = Modifier.size(70.dp)) { Icon(Icons.Default.Favorite, "Uložit", tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ListScreenV2(events: List<CulturalEvent>, reactions: ReactionStore, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(events, key = { it.id }) { e ->
            val r = reactions.get(e.id)
            Card(Modifier.fillMaxWidth().clickable { onOpen(e) }, shape = RoundedCornerShape(26.dp)) {
                Box(Modifier.fillMaxWidth().height(235.dp)) {
                    EventImage(e, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0050507)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(e.title, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${e.dateLabel} ${e.timeLabel} · ${e.city}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.venue, fontWeight = FontWeight.Bold); Text(e.source, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    IconButton(onClick = { onReact(e.id, if (r == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) { Icon(Icons.Default.VisibilityOff, null) }
                    FilledTonalIconButton(onClick = { onReact(e.id, if (r == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) { Icon(Icons.Default.Favorite, null) }
                }
            }
        }
    }
}

@Composable
private fun EventImage(e: CulturalEvent, modifier: Modifier = Modifier) {
    if (!e.imageUrl.isNullOrBlank()) AsyncImage(model = e.imageUrl, contentDescription = e.title, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color(0xFF102218), Color(0xFF050706)))), contentAlignment = Alignment.Center) { Icon(Icons.Default.ImageNotSupported, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) }
}

@Composable
private fun DetailScreen(e: CulturalEvent, reaction: Reaction, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                EventImage(e, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5050507)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(e.genre ?: e.type.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(e.title, fontSize = 31.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${e.dateLabel} ${e.timeLabel}", fontWeight = FontWeight.Bold)
                Text("${e.venue}, ${e.city}")
                Text(e.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onReact(if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }, modifier = Modifier.weight(1f)) { Text("Nechci") }
                    Button(onClick = { onReact(if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }, modifier = Modifier.weight(1f)) { Text("Uložit") }
                }
                e.ticketUrl?.let { url -> Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) { Text("Vstupenky / detail") } }
                Text("Zdroj: ${e.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreenV2(placeName: String, placeDetail: String, radius: Int, onRadius: (Int) -> Unit, mode: UiMode, onMode: (UiMode) -> Unit, apiKey: String, onApiKey: (String) -> Unit, onSaveApi: () -> Unit, onResetPlace: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var localRadius by remember(radius) { mutableIntStateOf(radius) }
    val accents = listOf("green" to Color(0xFF61F59A), "wine" to Color(0xFFFF5E89), "purple" to Color(0xFFC985FF), "blue" to Color(0xFF72A7FF), "red" to Color(0xFFFF6472), "gold" to Color(0xFFE4BC64))

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lokalita", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(placeName, color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    if (placeDetail.isNotBlank()) Text(placeDetail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Hledat do $localRadius km")
                    Slider(value = localRadius.toFloat(), onValueChange = { localRadius = it.roundToInt() }, onValueChangeFinished = { onRadius(localRadius) }, valueRange = 5f..200f, steps = 38)
                    OutlinedButton(onClick = onResetPlace, modifier = Modifier.fillMaxWidth()) { Text("Změnit město / vesnici") }
                }
            }
        }
        item {
            Text("Úvodní zobrazení", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { UiMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) }) } }
        }
        item {
            Text("Barva aplikace", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                accents.forEach { (id, color) ->
                    Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable { prefs.edit().putString("accent_theme", id).apply(); (context as? Activity)?.recreate() })
                }
            }
        }
        item {
            Text("Ticketmaster navíc", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("GoOut funguje i bez API. Ticketmaster může přidat další akce.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = apiKey, onValueChange = onApiKey, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSaveApi, modifier = Modifier.fillMaxWidth()) { Text("Uložit a obnovit") }
        }
        item { Text("Kulturadar 1.9", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
