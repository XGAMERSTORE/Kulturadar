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
import kotlin.math.roundToInt

private enum class HomeMode(val key: String, val label: String) { SWIPE("swipe", "Swipe"), LIST("cards", "Seznam") }
private enum class HomeTab(val label: String) { DISCOVER("Objevovat"), SAVED("Uložené"), SETTINGS("Nastavení") }

@Composable
fun KulturadarLocationApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }

    if (!onboardingDone) {
        KulturadarOnboarding(
            onDone = { place, radius, mode ->
                prefs.edit()
                    .putBoolean("onboarding_done", true)
                    .putString("home_place", place.name)
                    .putString("home_place_detail", place.detail)
                    .putLong("home_lat_bits", java.lang.Double.doubleToRawLongBits(place.latitude))
                    .putLong("home_lon_bits", java.lang.Double.doubleToRawLongBits(place.longitude))
                    .putInt("radius_km", radius)
                    .putString("view_mode", mode.key)
                    .apply()
                onboardingDone = true
            }
        )
        return
    }

    KulturadarHome(onChangePlace = {
        prefs.edit().putBoolean("onboarding_done", false).apply()
        onboardingDone = false
    })
}

@Composable
private fun KulturadarOnboarding(onDone: (CzechPlace, Int, HomeMode) -> Unit) {
    val placeRepo = remember { PlaceRepository() }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(HomeMode.SWIPE) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CzechPlace>>(emptyList()) }
    var selected by remember { mutableStateOf<CzechPlace?>(null) }
    var loading by remember { mutableStateOf(false) }
    var radius by remember { mutableIntStateOf(30) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF030705)) {
        LazyColumn(
            contentPadding = PaddingValues(24.dp, 42.dp, 24.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFF123C24), modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Radar, null, tint = Color(0xFF61F59A), modifier = Modifier.size(32.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kulturadar", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Text("Najdi kulturu kolem sebe", color = Color(0xFF9EB7A7))
                    }
                }
            }
            item {
                Text("Jak chceš objevovat?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { mode = item },
                            label = { Text(item.label) },
                            leadingIcon = { Icon(if (item == HomeMode.SWIPE) Icons.Default.Swipe else Icons.Default.ViewAgenda, null) }
                        )
                    }
                }
            }
            item {
                Text("Vyber město nebo vesnici", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Můžeš vyhledat jakoukoliv obec v Česku.", color = Color(0xFF9EB7A7))
                Spacer(Modifier.height(10.dp))
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
                                selected = null
                                scope.launch {
                                    results = withContext(Dispatchers.IO) { placeRepo.search(query) }
                                    loading = false
                                }
                            }
                        }) { Icon(Icons.Default.Search, "Vyhledat") }
                    },
                    placeholder = { Text("Např. Rychvald, Horní Lhota, Praha…") },
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
                            if (place.detail.isNotBlank()) Text(place.detail, color = Color(0xFF9EB7A7), fontSize = 13.sp)
                        }
                        if (selected == place) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF61F59A))
                    }
                }
            }
            item {
                Text("Jak daleko hledat?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Do $radius km od vybrané obce", color = Color(0xFF61F59A), fontWeight = FontWeight.Bold)
                Slider(
                    value = radius.toFloat(),
                    onValueChange = { radius = it.roundToInt() },
                    valueRange = 5f..200f,
                    steps = 38
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("5 km", color = Color(0xFF9EB7A7), fontSize = 12.sp)
                    Text("200 km", color = Color(0xFF9EB7A7), fontSize = 12.sp)
                }
            }
            item {
                Button(
                    onClick = { selected?.let { onDone(it, radius, mode) } },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Explore, null); Spacer(Modifier.width(8.dp)); Text("Začít objevovat", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun KulturadarHome(onChangePlace: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val repo = remember { EventRepository() }
    val cache = remember { EventCache(context) }
    val reactions = remember { ReactionStore(context) }
    val premium = BuildConfig.INTERNAL_PREMIUM
    val scope = rememberCoroutineScope()

    val homeName = prefs.getString("home_place", "Ostrava") ?: "Ostrava"
    val homeDetail = prefs.getString("home_place_detail", "") ?: ""
    val homeLat = java.lang.Double.longBitsToDouble(prefs.getLong("home_lat_bits", java.lang.Double.doubleToRawLongBits(49.8209)))
    val homeLon = java.lang.Double.longBitsToDouble(prefs.getLong("home_lon_bits", java.lang.Double.doubleToRawLongBits(18.2625)))
    var radius by remember { mutableIntStateOf(prefs.getInt("radius_km", 30)) }
    var mode by remember { mutableStateOf(if (prefs.getString("view_mode", "swipe") == "cards") HomeMode.LIST else HomeMode.SWIPE) }
    var tab by remember { mutableStateOf(HomeTab.DISCOVER) }
    var apiKey by remember { mutableStateOf(prefs.getString("tm_key", "") ?: "") }
    var events by remember { mutableStateOf(cache.load().ifEmpty { repo.demoEvents() }) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CulturalEvent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reactionTick by remember { mutableIntStateOf(0) }

    fun inRadius(event: CulturalEvent): Boolean {
        val lat = event.latitude
        val lon = event.longitude
        if (lat != null && lon != null) return PlaceRepository.distanceKm(homeLat, homeLon, lat, lon) <= radius
        return event.city.equals(homeName, true)
    }

    fun refresh() {
        loading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { repo.loadAllSources(apiKey.trim(), "Všechna města", search) }
            val nearby = result.events.filter(::inRadius)
            if (nearby.isNotEmpty()) {
                events = nearby
                withContext(Dispatchers.IO) { cache.save(nearby) }
            }
            message = when {
                nearby.isNotEmpty() -> result.error
                result.events.isNotEmpty() -> "V okruhu $radius km od $homeName jsem teď nic nenašel. Zkus větší vzdálenost."
                else -> result.error ?: "Akce se nepodařilo načíst."
            }
            loading = false
        }
    }

    LaunchedEffect(homeName, radius) { refresh() }

    BackHandler(enabled = selected != null || tab != HomeTab.DISCOVER) {
        if (selected != null) selected = null else tab = HomeTab.DISCOVER
    }

    val visible = remember(events, tab, search, reactionTick) {
        events.filter { event ->
            val reaction = reactions.get(event.id)
            val tabOk = tab != HomeTab.SAVED || reaction == Reaction.LOVED
            val q = search.trim()
            val qOk = q.isBlank() || listOf(event.title, event.subtitle, event.venue, event.city, event.genre.orEmpty()).any { it.contains(q, true) }
            tabOk && qOk && reaction != Reaction.HATED
        }.sortedBy { it.dateLabel + it.timeLabel }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF2050907)) {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; selected = null },
                        icon = { Icon(when (item) {
                            HomeTab.DISCOVER -> Icons.Default.Explore
                            HomeTab.SAVED -> Icons.Default.Favorite
                            HomeTab.SETTINGS -> Icons.Default.Settings
                        }, null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> EventDetailV2(selected!!, reactions.get(selected!!.id), onBack = { selected = null }, onReact = { reactions.set(selected!!.id, it); reactionTick++ }, Modifier.padding(padding))
            tab == HomeTab.SETTINGS -> SettingsV2(
                homeName = homeName,
                homeDetail = homeDetail,
                radius = radius,
                onRadius = {
                    radius = it
                    prefs.edit().putInt("radius_km", it).apply()
                },
                mode = mode,
                onMode = {
                    mode = it
                    prefs.edit().putString("view_mode", it.key).apply()
                },
                apiKey = apiKey,
                onApiKey = { apiKey = it },
                onSaveApi = {
                    prefs.edit().putString("tm_key", apiKey.trim()).apply()
                    refresh()
                },
                onChangePlace = onChangePlace,
                modifier = Modifier.padding(padding)
            )
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                HomeHeader(homeName, radius, search, { search = it }, loading, ::refresh)
                message?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                if (mode == HomeMode.SWIPE && tab == HomeTab.DISCOVER) {
                    SwipeV2(visible, reactions, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); reactionTick++ }, Modifier.weight(1f))
                } else {
                    EventListV2(visible, reactions, onOpen = { selected = it }, onReact = { id, r -> reactions.set(id, r); reactionTick++ }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(place: String, radius: Int, search: String, onSearch: (String) -> Unit, loading: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kulturadar", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$place · do $radius km", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Obnovit") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Hledat akci, interpreta, místo…") },
            shape = RoundedCornerShape(24.dp)
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun SwipeV2(events: List<CulturalEvent>, reactions: ReactionStore, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    var index by remember(events) { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    if (events.isEmpty() || index >= events.size) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (events.isEmpty()) "V okolí teď nic není." else "Prošel jsi všechny akce.") }
        return
    }
    val event = events[index]
    fun next(r: Reaction) { onReact(event.id, r); dragX = 0f; index++ }
    Column(modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            Modifier.fillMaxWidth().weight(1f)
                .graphicsLayer { translationX = dragX; rotationZ = dragX / 45f }
                .pointerInput(event.id) {
                    detectDragGestures(onDragEnd = {
                        when { dragX > 120f -> next(Reaction.LOVED); dragX < -120f -> next(Reaction.HATED); else -> dragX = 0f }
                    }) { change, amount -> change.consume(); dragX += amount.x }
                }
                .clickable { onOpen(event) },
            shape = RoundedCornerShape(30.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                EventImageV2(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xF0050507)))))
                Surface(Modifier.align(Alignment.TopStart).padding(16.dp), color = Color(0xCC07100B), shape = RoundedCornerShape(12.dp)) {
                    Text(event.genre ?: event.type.title, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
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
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun EventListV2(events: List<CulturalEvent>, reactions: ReactionStore, onOpen: (CulturalEvent) -> Unit, onReact: (String, Reaction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(events, key = { it.id }) { event ->
            val reaction = reactions.get(event.id)
            Card(Modifier.fillMaxWidth().clickable { onOpen(event) }, shape = RoundedCornerShape(26.dp)) {
                Box(Modifier.fillMaxWidth().height(245.dp)) {
                    EventImageV2(event, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0050507)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(event.title, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${event.dateLabel} ${event.timeLabel} · ${event.city}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.venue, fontWeight = FontWeight.Bold)
                        Text(event.genre ?: event.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onReact(event.id, if (reaction == Reaction.HATED) Reaction.NONE else Reaction.HATED) }) { Icon(Icons.Default.VisibilityOff, null) }
                    FilledTonalIconButton(onClick = { onReact(event.id, if (reaction == Reaction.LOVED) Reaction.NONE else Reaction.LOVED) }) { Icon(Icons.Default.Favorite, null) }
                }
            }
        }
    }
}

@Composable
private fun EventImageV2(event: CulturalEvent, modifier: Modifier = Modifier) {
    if (!event.imageUrl.isNullOrBlank()) {
        AsyncImage(model = event.imageUrl, contentDescription = event.title, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color(0xFF102218), Color(0xFF050706)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ImageNotSupported, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun EventDetailV2(event: CulturalEvent, reaction: Reaction, onBack: () -> Unit, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(350.dp)) {
                EventImageV2(event, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5050507)))))
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, "Zpět") }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(event.genre ?: event.type.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(event.title, fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailRow(Icons.Default.CalendarMonth, "${event.dateLabel} ${event.timeLabel}")
                DetailRow(Icons.Default.LocationOn, "${event.venue}, ${event.city}")
                DetailRow(Icons.Default.Payments, event.priceCzk?.let { "od $it Kč" } ?: "Cena neuvedena")
                HorizontalDivider()
                Text("O akci", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(event.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { shareEventV2(context, event) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Sdílet") }
                    OutlinedButton(onClick = { addCalendarV2(context, event) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Event, null); Spacer(Modifier.width(6.dp)); Text("Kalendář") }
                }
                event.ticketUrl?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) { Text("Vstupenky / detail") }
                }
                Text("Zdroj: ${event.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SettingsV2(
    homeName: String,
    homeDetail: String,
    radius: Int,
    onRadius: (Int) -> Unit,
    mode: HomeMode,
    onMode: (HomeMode) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    onSaveApi: () -> Unit,
    onChangePlace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var localRadius by remember(radius) { mutableIntStateOf(radius) }
    var accent by remember { mutableStateOf(prefs.getString("accent_theme", "green") ?: "green") }
    val accents = listOf(
        "green" to Color(0xFF61F59A), "wine" to Color(0xFFFF5E89), "purple" to Color(0xFFC985FF),
        "blue" to Color(0xFF72A7FF), "red" to Color(0xFFFF6472), "gold" to Color(0xFFE4BC64)
    )

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Nastavení", fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Domovská lokalita", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(homeName, color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    if (homeDetail.isNotBlank()) Text(homeDetail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Hledat do $localRadius km")
                    Slider(value = localRadius.toFloat(), onValueChange = { localRadius = it.roundToInt() }, onValueChangeFinished = { onRadius(localRadius) }, valueRange = 5f..200f, steps = 38)
                    OutlinedButton(onClick = onChangePlace, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.EditLocation, null); Spacer(Modifier.width(8.dp)); Text("Změnit město / vesnici") }
                }
            }
        }
        item {
            Text("Zobrazení", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { onMode(item) }, label = { Text(item.label) }) }
            }
        }
        item {
            Text("Barva aplikace", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                accents.forEach { (id, color) ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(color)
                            .clickable {
                                accent = id
                                prefs.edit().putString("accent_theme", id).apply()
                                (context as? Activity)?.recreate()
                            }
                    )
                }
            }
        }
        item {
            Text("Volitelný Ticketmaster", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("GoOut funguje i bez API klíče. Ticketmaster může přidat další akce.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = apiKey, onValueChange = onApiKey, label = { Text("Ticketmaster API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSaveApi, modifier = Modifier.fillMaxWidth()) { Text("Uložit a obnovit") }
        }
        item { Text("Kulturadar 1.9 · lokalita + okruh", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun shareEventV2(context: Context, event: CulturalEvent) {
    val text = buildString {
        append(event.title).append("\n").append(event.dateLabel).append(" ").append(event.timeLabel)
        append(" · ").append(event.venue).append(", ").append(event.city)
        event.ticketUrl?.let { append("\n").append(it) }
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Sdílet akci"))
}

private fun addCalendarV2(context: Context, event: CulturalEvent) {
    context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, "${event.venue}, ${event.city}")
        .putExtra(CalendarContract.Events.DESCRIPTION, event.ticketUrl ?: event.description))
}
