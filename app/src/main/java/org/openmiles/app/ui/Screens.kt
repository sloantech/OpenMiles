@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.openmiles.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openmiles.app.AppConfig
import org.openmiles.app.AppViewModel
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.TripSummary
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.Csv
import org.openmiles.app.tracking.LiveTrip
import org.openmiles.app.tracking.TrackingService
import java.io.File
import java.time.LocalDate

// ---------------------------------------------------------------------------
// App shell with three tabs
// ---------------------------------------------------------------------------

@Composable
fun OpenMilesApp(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Drives" to Icons.Default.Home,
        "Reports" to Icons.Default.DateRange,
        "Settings" to Icons.Default.Settings
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DrivesScreen(vm)
                1 -> ReportsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Drives tab: record, swipe to classify, tap to edit
// ---------------------------------------------------------------------------

@Composable
fun DrivesScreen(vm: AppViewModel) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingId by remember { mutableStateOf<Long?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            permissionDenied = false
            TrackingService.start(context)
        } else {
            permissionDenied = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        RecordCard(
            live = live,
            permissionDenied = permissionDenied,
            onStart = {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionLauncher.launch(perms.toTypedArray())
            },
            onStop = { TrackingService.stop(context) }
        )

        if (trips.isEmpty()) {
            Text(
                "No drives yet. Tap Start drive before you leave, then Stop when you arrive.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Text(
                "Swipe right for Business. Swipe left for Personal. Tap a drive for more choices.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        category = categories.find { it.id == trip.categoryId },
                        rates = rates,
                        onSwipeBusiness = { vm.quickClassify(trip.id, Kind.BUSINESS) },
                        onSwipePersonal = { vm.quickClassify(trip.id, Kind.PERSONAL) },
                        onClick = { editingId = trip.id }
                    )
                }
            }
        }
    }

    val editing = trips.find { it.id == editingId }
    if (editing != null) {
        TripDialog(
            trip = editing,
            categories = categories,
            onPick = { vm.setCategory(editing.id, it) },
            onSaveNotes = { vm.setNotes(editing.id, it) },
            onDelete = {
                vm.deleteTrip(editing.id)
                editingId = null
            },
            onClose = { editingId = null }
        )
    }
}

@Composable
private fun RecordCard(
    live: LiveTrip,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (live.active) {
                Text("Recording your drive", style = MaterialTheme.typography.titleMedium)
                Text(
                    Calc.fmtMiles(live.meters),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text("Started at ${Calc.fmtTime(live.startTime)}")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop drive") }
            } else {
                Text("Ready when you are", style = MaterialTheme.typography.titleMedium)
                if (permissionDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "OpenMiles needs location permission to record drives. " +
                            "Turn it on in your phone's Settings, under Apps, OpenMiles, Permissions.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start drive") }
            }
        }
    }
}

@Composable
private fun TripRow(
    trip: TripSummary,
    category: Category?,
    rates: List<Rate>,
    onSwipeBusiness: () -> Unit,
    onSwipePersonal: () -> Unit,
    onClick: () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeBusiness()
                SwipeToDismissBoxValue.EndToStart -> onSwipePersonal()
                else -> Unit
            }
            // Always snap back. The card stays and shows its new category.
            false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            val (color, label, alignment) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Triple(Color(0xFF2E7D32), "Business", Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart ->
                    Triple(Color(0xFF546E7A), "Personal", Alignment.CenterEnd)
                else -> Triple(Color.Transparent, "", Alignment.Center)
            }
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Calc.fmtDate(trip.startTime), fontWeight = FontWeight.Bold)
                    Text(
                        "${Calc.fmtTime(trip.startTime)} to ${Calc.fmtTime(trip.endTime)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        category?.name ?: "Not classified yet",
                        color = if (category == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (trip.notes.isNotBlank()) {
                        Text(trip.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Calc.fmtMiles(trip.distanceMeters),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(Calc.fmtMoney(Calc.valueOf(trip, category, rates)))
                }
            }
        }
    }
}

@Composable
private fun TripDialog(
    trip: TripSummary,
    categories: List<Category>,
    onPick: (Long?) -> Unit,
    onSaveNotes: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    var notes by remember(trip.id) { mutableStateOf(trip.notes) }
    var confirmDelete by remember(trip.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            onSaveNotes(notes)
            onClose()
        },
        title = { Text("${Calc.fmtDate(trip.startTime)}, ${Calc.fmtMiles(trip.distanceMeters)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Category", fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = trip.categoryId == cat.id,
                            onClick = { onPick(cat.id) },
                            label = { Text(cat.name) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(AppViewModel.MAX_NOTES) },
                    label = { Text("Notes (client, purpose)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveNotes(notes)
                onClose()
            }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete drive", color = MaterialTheme.colorScheme.error)
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this drive?") },
            text = { Text("This removes it from your log and your reports. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Reports tab: totals by category and CSV export
// ---------------------------------------------------------------------------

@Composable
fun ReportsScreen(vm: AppViewModel) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }

    val yearTrips = trips.filter { Calc.toDate(it.startTime).year == year }
    val byId = categories.associateBy { it.id }
    val unclassified = yearTrips.count { it.categoryId == null || byId[it.categoryId] == null }
    val totalValue = yearTrips.sumOf { Calc.valueOf(it, byId[it.categoryId], rates) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { year -= 1 }) { Text("<") }
            Text("$year", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { year += 1 }) { Text(">") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Total value of classified drives")
                Text(
                    Calc.fmtMoney(totalValue),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text("${yearTrips.size} drives, ${Calc.fmtMiles(yearTrips.sumOf { it.distanceMeters })}")
                if (unclassified > 0) {
                    Text(
                        "$unclassified drives still need a category.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text("By category", style = MaterialTheme.typography.titleMedium)
        categories.forEach { cat ->
            val mine = yearTrips.filter { it.categoryId == cat.id }
            if (mine.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${Kind.label(cat.kind)}, ${mine.size} drives",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Calc.fmtMiles(mine.sumOf { it.distanceMeters }))
                        Text(Calc.fmtMoney(mine.sumOf { Calc.valueOf(it, cat, rates) }))
                    }
                }
                HorizontalDivider()
            }
        }

        Button(
            onClick = { shareCsv(context, year, trips, categories, rates) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export $year as spreadsheet (CSV)") }
    }
}

private fun shareCsv(
    context: Context,
    year: Int,
    trips: List<TripSummary>,
    categories: List<Category>,
    rates: List<Rate>
) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "openmiles-$year.csv")
        file.writeText(Csv.build(year, trips, categories, rates))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("OpenMiles log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share mileage log"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not create the spreadsheet.", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------------------
// Settings tab: categories, rates, support
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddCategory by remember { mutableStateOf(false) }
    var showAddRate by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { Text("Categories", style = MaterialTheme.typography.titleLarge) }
        items(categories, key = { "c${it.id}" }) { cat ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cat.name, fontWeight = FontWeight.Bold)
                    Text(Kind.label(cat.kind), style = MaterialTheme.typography.bodySmall)
                }
                if (!cat.builtIn) {
                    TextButton(onClick = { vm.deleteCategory(cat) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { showAddCategory = true }) { Text("Add your own category") }
            Spacer(Modifier.height(16.dp))
            Text("Mileage rates", style = MaterialTheme.typography.titleLarge)
            Text(
                "Rates are in dollars per mile. Each rate starts on a date, so old drives keep the old rate. " +
                    "Check irs.gov each year for new IRS rates.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(rates, key = { "r${it.id}" }) { rate ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${Kind.label(rate.kind)} from ${rate.effectiveFrom}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(java.util.Locale.US, "\$%.3f", rate.dollarsPerMile))
                    TextButton(onClick = { vm.deleteRate(rate) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { showAddRate = true }) { Text("Add a rate") }
            Spacer(Modifier.height(16.dp))
            Text("About OpenMiles", style = MaterialTheme.typography.titleLarge)
            Text(
                "Free and open source. No ads, no account, no subscription. " +
                    "This app has no internet access, so your drives never leave this phone.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (AppConfig.DONATE_URL.isNotBlank()) {
                Button(onClick = { openLink(context, AppConfig.DONATE_URL) }) {
                    Text("Support the developer")
                }
            }
            if (AppConfig.SOURCE_URL.isNotBlank()) {
                TextButton(onClick = { openLink(context, AppConfig.SOURCE_URL) }) {
                    Text("View the source code")
                }
            }
        }
    }

    if (showAddCategory) {
        AddCategoryDialog(
            onAdd = { name, kind -> vm.addCategory(name, kind) },
            onClose = { showAddCategory = false }
        )
    }
    if (showAddRate) {
        AddRateDialog(
            onAdd = { kind, date, dollars -> vm.addRate(kind, date, dollars) },
            onClose = { showAddRate = false }
        )
    }
}

private fun openLink(context: Context, url: String) {
    // Only web links are opened, whatever is typed into AppConfig.
    if (!url.startsWith("https://")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open this link.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AddCategoryDialog(onAdd: (String, String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.BUSINESS) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AppViewModel.MAX_CATEGORY_NAME) },
                    label = { Text("Name, like Client visit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Counts as")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kind.all.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(Kind.label(k)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onAdd(name, kind)
                    onClose()
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun AddRateDialog(onAdd: (String, String, Double) -> Unit, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(Kind.BUSINESS) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var dollars by remember { mutableStateOf("") }
    val dateOk = runCatching { LocalDate.parse(date) }.isSuccess
    // Accept a comma as the decimal mark too, since some phones use one.
    val amount = dollars.trim().replace(',', '.').toDoubleOrNull()
    // A rate above $10 per mile is almost certainly a typo, like 76 instead of 0.76.
    val amountOk = amount != null && amount >= 0.0 && amount <= 10.0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New rate") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Kind.BUSINESS, Kind.MEDICAL, Kind.CHARITY).forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(Kind.label(k)) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Starts on (YYYY-MM-DD)") },
                    isError = !dateOk,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dollars,
                    onValueChange = { dollars = it },
                    label = { Text("Dollars per mile, like 0.76") },
                    isError = dollars.isNotBlank() && !amountOk,
                    supportingText = {
                        if (dollars.isNotBlank() && !amountOk) {
                            Text("Enter a number from 0 to 10, like 0.76")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dateOk && amountOk,
                onClick = {
                    onAdd(kind, date, amount ?: 0.0)
                    onClose()
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
