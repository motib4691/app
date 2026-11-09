package il.co.israeltransit.offline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import il.co.israeltransit.offline.ui.OperatorTheme
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StopSearchScreen(
    onBack: () -> Unit,
    onNavigateToLine: (String) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }

    var query by remember { mutableStateOf("") }
    var groupedResults by remember { mutableStateOf(emptyList<TransitRepository.DualStopGroup>()) }
    var selectedStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var oppositeStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    var departures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }
    var lines by remember { mutableStateOf(emptyList<TransitRepository.TransitLine>()) }
    var departuresLimit by remember { mutableIntStateOf(10) }
    var selectedTabMode by remember { mutableStateOf("עכשיו") }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            groupedResults = emptyList()
        } else {
            delay(200)
            groupedResults = withContext(Dispatchers.IO) { repository.searchGroupedStops(q, 30) }
        }
    }

    LaunchedEffect(selectedStop) {
        val stop = selectedStop
        if (stop != null) {
            oppositeStop = withContext(Dispatchers.IO) { repository.findOppositeStop(stop) }
            departures = withContext(Dispatchers.IO) { scheduleRepo.upcomingDepartures(stop.stopId, limit = departuresLimit) }
            lines = withContext(Dispatchers.IO) { repository.getLinesForStop(stop.stopId, 100) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selectedStop == null) "חיפוש תחנה" else "פרטי תחנה",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = { if (selectedStop != null) selectedStop = null else onBack() }, shape = RoundedCornerShape(20.dp)) {
                Text(if (selectedStop != null) "חזרה לחיפוש" else "חזרה")
            }
        }

        if (selectedStop == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("שם תחנה, רחוב או מספר (מק\"ט)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            groupedResults.forEach { group ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column {
                        // תחנה 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStop = group.stop1 }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(group.stop1.name, fontWeight = FontWeight.Bold)
                            Text("תחנה ${group.stop1.stopCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        // תחנה 2 נגדית אם קיימת
                        if (group.stop2 != null) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedStop = group.stop2 }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${group.stop2.name} (לכיוון השני)", fontWeight = FontWeight.Bold)
                                Text("תחנה ${group.stop2.stopCode}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            val stop = selectedStop!!
            Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("מספר תחנה (מק\"ט): ${stop.stopCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onPlanRouteFrom?.let {
                    FilledTonalButton(onClick = { it(stop) }, shape = RoundedCornerShape(20.dp)) { Text("מסלול מכאן", fontSize = 12.sp) }
                }
                onPlanRouteTo?.let {
                    FilledTonalButton(onClick = { it(stop) }, shape = RoundedCornerShape(20.dp)) { Text("מסלול לכאן", fontSize = 12.sp) }
                }
                if (oppositeStop != null) {
                    OutlinedButton(onClick = { selectedStop = oppositeStop }, shape = RoundedCornerShape(20.dp)) {
                        Text("תחנה נגדית ⟷", fontSize = 12.sp)
                    }
                }
            }

            // חלונית 1: יציאות קרובות עם טיימר ירוק חי
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("אוטובוסים קרובים בתחנה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    departures.forEach { dep ->
                        val diff = Duration.between(LocalDateTime.now(), dep.departureAt).toMinutes().coerceAtLeast(0)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToLine(dep.lineNumber) }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TransitLineBadge(lineNumber = dep.lineNumber, agencyName = dep.agencyName)
                                Text(dep.headsign, fontSize = 13.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(dep.departureAt.format(timeFormat), fontWeight = FontWeight.Bold)
                                Text("עוד $diff דק'", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            departuresLimit += 20
                            selectedStop = stop
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("הצג 20 נסיעות נוספות")
                    }
                }
            }

            // חלונית 2: כל הקווים בתחנה
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("קווים העוברים בתחנה זו", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToLine(line.lineNumber) }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                                Column {
                                    Text(line.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(line.agencyName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("בעוד 5 • 15 • 25 דק'", color = Color(0xFF2E7D32), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineSearchScreen(
    initialLine: String? = null,
    onBack: () -> Unit,
    onNavigateToStop: (TransitRepository.TransitStop) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }

    var query by remember { mutableStateOf(initialLine ?: "") }
    var lines by remember { mutableStateOf(emptyList<TransitRepository.TransitLine>()) }
    var selectedLine by remember { mutableStateOf<TransitRepository.TransitLine?>(null) }
    var expandedLineId by remember { mutableStateOf<String?>(null) }

    var stops by remember { mutableStateOf(emptyList<TransitRepository.RouteStop>()) }
    var dailyDepartures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }
    var selectedDayType by remember { mutableIntStateOf(0) } // 0=א-ה, 1=ו, 2=שבת
    var selectedStopIndex by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialLine.isNullOrBlank()) {
            delay(200)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            lines = emptyList()
        } else {
            delay(200)
            lines = withContext(Dispatchers.IO) { repository.searchLines(q, 30) }
        }
    }

    LaunchedEffect(selectedLine, selectedDayType) {
        val line = selectedLine
        if (line != null) {
            stops = withContext(Dispatchers.IO) { repository.getStopsForLine(line.routeId, 200) }
            dailyDepartures = withContext(Dispatchers.IO) { scheduleRepo.getDailyDepartures(line.routeId, selectedDayType) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selectedLine == null) "חיפוש קו" else "פרטי קו ${selectedLine!!.lineNumber}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = { if (selectedLine != null) selectedLine = null else onBack() }, shape = RoundedCornerShape(20.dp)) {
                Text(if (selectedLine != null) "חזרה לרשימה" else "חזרה")
            }
        }

        if (selectedLine == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("מספר קו") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            lines.forEach { line ->
                val isExpanded = expandedLineId == line.routeId

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { expandedLineId = if (isExpanded) null else line.routeId },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                                Column {
                                    Text("${line.originName} ⟷ ${line.destinationName}", fontWeight = FontWeight.Bold)
                                    Text(line.agencyName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(if (isExpanded) "▲" else "▼")
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = { selectedLine = line },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("כיוון: מ-${line.originName} ל-${line.destinationName}")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val line = selectedLine!!

            // לוח זמנים יומי בבועות
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("לוח זמנים יומי", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "א'-ה' (היום)", 1 to "יום ו'", 2 to "מוצ\"ש").forEach { (type, label) ->
                            FilterChip(
                                selected = selectedDayType == type,
                                onClick = { selectedDayType = type },
                                label = { Text(label, fontSize = 12.sp) },
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dailyDepartures.forEach { dep ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (dep.isLastFridayTrip || dep.isFirstSaturdayTrip) Color(0xFFF57F17).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Text(
                                    text = dep.departureAt.format(timeFormat),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ציר תחנות גרפי
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("מסלול ותחנות הקו (${stops.size} תחנות)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    stops.forEachIndexed { idx, st ->
                        val isSelected = selectedStopIndex == idx
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStopIndex = idx }
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${idx + 1}", fontSize = 11.sp, color = if (isSelected) Color.White else Color.Black)
                            }
                            Text(st.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
