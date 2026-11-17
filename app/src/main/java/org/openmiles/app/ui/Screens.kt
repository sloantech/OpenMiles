@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.openmiles.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.openmiles.app.AppConfig
import org.openmiles.app.AppViewModel
import org.openmiles.app.BuildConfig
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Source
import org.openmiles.app.data.TripSummary
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.Csv
import org.openmiles.app.domain.DateRange
import org.openmiles.app.domain.ImportedDrive
import org.openmiles.app.domain.TimelineImport
import org.openmiles.app.domain.UpdateReminder
import org.openmiles.app.domain.Validation
import org.openmiles.app.tracking.AutoDetect
import org.openmiles.app.tracking.DiagLog
import org.openmiles.app.tracking.LiveTrip
import org.openmiles.app.tracking.TrackingService

// ---------------------------------------------------------------------------
// App shell with three tabs
// ---------------------------------------------------------------------------

@Composable
fun OpenMilesApp(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Drives" to HighwayIcons.Car,
        "Reports" to HighwayIcons.Chart,
        "Settings" to HighwayIcons.Gear
    )
    Scaffold(
        topBar = { HighwayHeader() },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
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

/** The navy bar at the top, with the app name and whether auto-detect is on. */
@Composable
private fun HighwayHeader() {
    val extra = LocalExtraColors.current
    val context = LocalContext.current
    var autoOn by remember { mutableStateOf(AutoDetect.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) autoOn = AutoDetect.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxWidth().background(extra.header)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Highway.Amber),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(HighwayIcons.Road, contentDescription = null, tint = Highway.OnAmber, modifier = Modifier.size(22.dp))
                }
                Text(
                    "OpenMiles",
                    color = extra.onHeader,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (autoOn) {
                Text(
                    "Auto-detect on",
                    color = extra.headerSubtext,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun DrivesScreen(vm: AppViewModel) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingId by remember { mutableStateOf<Long?>(null) }
    var permissionProblem by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionProblem = when {
            fine -> null
            // Approximate location is only accurate to about a mile, so every reading
            // would be rejected and the drive thrown away. Ask for precise instead.
            coarse -> "OpenMiles needs Precise location to measure miles. " +
                "In your phone's Settings, open Apps, OpenMiles, Permissions, Location, " +
                "and turn on Use precise location."
            else -> "OpenMiles needs location permission to record drives. " +
                "Turn it on in your phone's Settings, under Apps, OpenMiles, Permissions."
        }
        if (fine) TrackingService.start(context)
    }

    var addingDrive by remember { mutableStateOf(false) }
    val reminderPrefs = remember { context.getSharedPreferences("update_reminder", Context.MODE_PRIVATE) }
    var snoozedUntil by remember { mutableLongStateOf(reminderPrefs.getLong("until", 0L)) }
    val now = System.currentTimeMillis()

    val today = LocalDate.now()
    val grouped = remember(trips) {
        trips.groupBy { Calc.toDate(it.startTime) }.toList().sortedByDescending { it.first }
    }
    val byCategory = remember(categories) { categories.associateBy { it.id } }
    val monthTrips = remember(trips) {
        trips.filter { Calc.toDate(it.startTime) in DateRange.month(today) }
    }
    val monthBusiness = remember(monthTrips, byCategory, rates) {
        monthTrips.sumOf { t ->
            val c = t.categoryId?.let { byCategory[it] }
            if (c?.kind == Kind.BUSINESS) Calc.valueOf(t, c, rates) else 0.0
        }
    }
    val toClassify = remember(trips, byCategory) {
        trips.count { it.categoryId == null || byCategory[it.categoryId] == null }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (UpdateReminder.shouldShow(now, BuildConfig.BUILD_TIME_MS, snoozedUntil)) {
            item(key = "update") {
                UpdateBanner(
                    monthsOld = UpdateReminder.monthsOld(now, BuildConfig.BUILD_TIME_MS),
                    onCheck = {
                        openLink(context, AppConfig.RELEASES_URL)
                        snoozedUntil = now + UpdateReminder.AFTER_CHECK_DAYS * UpdateReminder.DAY_MS
                        reminderPrefs.edit().putLong("until", snoozedUntil).apply()
                    },
                    onLater = {
                        snoozedUntil = now + UpdateReminder.SNOOZE_DAYS * UpdateReminder.DAY_MS
                        reminderPrefs.edit().putLong("until", snoozedUntil).apply()
                    }
                )
            }
        }
        item(key = "summary") {
            MonthSummary(
                monthName = today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US),
                meters = monthTrips.sumOf { it.distanceMeters },
                drives = monthTrips.size,
                businessValue = monthBusiness,
                toClassify = toClassify
            )
        }
        item(key = "record") {
            RecordCard(
                live = live,
                permissionProblem = permissionProblem,
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
        }
        item(key = "add") {
            TextButton(onClick = { addingDrive = true }) {
                Icon(HighwayIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a drive by hand", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (trips.isEmpty()) {
            item(key = "empty") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No drives yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap Start drive before you leave and Stop when you arrive. Or turn on " +
                                "automatic detection in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item(key = "hint") {
                Text(
                    "Swipe right for Business, left for Personal. Tap a drive for more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            grouped.forEach { (day, dayTrips) ->
                item(key = "d$day") {
                    Text(
                        dayLabel(day, today).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(dayTrips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        category = trip.categoryId?.let { byCategory[it] },
                        rates = rates,
                        onSwipeBusiness = { vm.quickClassify(trip.id, Kind.BUSINESS) },
                        onSwipePersonal = { vm.quickClassify(trip.id, Kind.PERSONAL) },
                        onClick = { editingId = trip.id }
                    )
                }
            }
        }
    }

    if (addingDrive) {
        AddDriveDialog(
            categories = categories,
            onAdd = { start, end, miles, categoryId, notes ->
                vm.addManualTrip(start, end, miles, categoryId, notes)
                addingDrive = false
            },
            onClose = { addingDrive = false }
        )
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

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", java.util.Locale.US))
}

/** The navy card at the top: this month's miles, business value, and drives left to classify. */
@Composable
private fun MonthSummary(monthName: String, meters: Double, drives: Int, businessValue: Double, toClassify: Int) {
    val extra = LocalExtraColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = extra.header, contentColor = extra.onHeader),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                monthName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = extra.headerSubtext,
                letterSpacing = 1.sp
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            String.format(java.util.Locale.US, "%,.1f", Calc.miles(meters)),
                            style = BigNumber.copy(fontSize = 40.sp, letterSpacing = (-1).sp),
                            color = extra.onHeader
                        )
                        Text(
                            " mi",
                            style = BigNumber.copy(fontSize = 16.sp),
                            color = extra.onHeader,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Text(
                        if (drives == 1) "1 drive" else "$drives drives",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.headerSubtext
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Calc.fmtMoney(businessValue), style = BigNumber.copy(fontSize = 26.sp), color = extra.onHeader)
                    Text("business value", style = MaterialTheme.typography.bodySmall, color = extra.headerSubtext)
                }
            }
            if (toClassify > 0) {
                Pill(
                    if (toClassify == 1) "1 drive to classify" else "$toClassify drives to classify",
                    extra.todo,
                    extra.todoBg
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, background: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun RecordCard(
    live: LiveTrip,
    permissionProblem: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val extra = LocalExtraColors.current
    if (live.active) {
        Card(
            colors = CardDefaults.cardColors(containerColor = extra.header, contentColor = extra.onHeader),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    if (live.auto) "Drive detected" else "Recording",
                    Highway.OnAmber,
                    Highway.Amber
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(java.util.Locale.US, "%.1f", Calc.miles(live.meters)),
                        style = BigNumber.copy(fontSize = 44.sp),
                        color = extra.onHeader
                    )
                    Text(
                        " mi",
                        style = BigNumber.copy(fontSize = 18.sp),
                        color = extra.onHeader,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Text(
                    "Started at ${Calc.fmtTime(live.startTime)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.headerSubtext
                )
                if (live.gpsWeak) {
                    Text(
                        "No GPS signal right now. Recording continues, and the gap is counted in a " +
                            "straight line when the signal returns.",
                        color = Highway.Amber,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Highway.Navy)
                ) {
                    Icon(HighwayIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop drive", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Highway.Amber, contentColor = Highway.OnAmber)
            ) {
                Icon(HighwayIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Start drive", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            if (permissionProblem != null) {
                Text(permissionProblem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
    val extra = LocalExtraColors.current
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
    val shape = RoundedCornerShape(16.dp)
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.clip(shape),
        backgroundContent = {
            val (color, label, alignment) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(extra.swipeBusiness, "Business", Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(extra.swipePersonal, "Personal", Alignment.CenterEnd)
                else -> Triple(Color.Transparent, "", Alignment.Center)
            }
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 22.dp),
                contentAlignment = alignment
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${Calc.fmtTime(trip.startTime)} to ${Calc.fmtTime(trip.endTime)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            category == null -> Pill("Swipe to classify", extra.todo, extra.todoBg)
                            category.kind == Kind.BUSINESS -> Pill(category.name, extra.business, extra.businessBg)
                            category.kind == Kind.PERSONAL -> Pill(category.name, extra.personal, extra.personalBg)
                            else -> Pill(category.name, extra.other, extra.otherBg)
                        }
                        if (trip.source != Source.GPS) {
                            Text(
                                Source.label(trip.source),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (trip.notes.isNotBlank()) {
                        Text(
                            trip.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            String.format(java.util.Locale.US, "%.1f", Calc.miles(trip.distanceMeters)),
                            style = BigNumber,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            " mi",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        if (category == null) "Not classified" else Calc.fmtMoney(Calc.valueOf(trip, category, rates)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Text(
                    "${Calc.fmtTime(trip.startTime)} to ${Calc.fmtTime(trip.endTime)}. Source: ${Source.label(trip.source)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
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
// Reports tab: totals by category for any date range, and CSV export
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(vm: AppViewModel) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now()
    val thisYear = DateRange.year(today.year)
    var fromDay by rememberSaveable { mutableLongStateOf(thisYear.from.toEpochDay()) }
    var toDay by rememberSaveable { mutableLongStateOf(thisYear.to.toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf("") } // "", "from", or "to"

    val range = DateRange(LocalDate.ofEpochDay(fromDay), LocalDate.ofEpochDay(maxOf(fromDay, toDay)))
    val rangeTrips = trips.filter { Calc.toDate(it.startTime) in range }
    val byId = categories.associateBy { it.id }
    val unclassified = rangeTrips.count { it.categoryId == null || byId[it.categoryId] == null }
    val totalValue = rangeTrips.sumOf { Calc.valueOf(it, byId[it.categoryId], rates) }

    val presets = listOf(
        "This month" to DateRange.month(today),
        "Last month" to DateRange.lastMonth(today),
        "This quarter" to DateRange.quarter(today),
        "Last quarter" to DateRange.lastQuarter(today),
        "This year" to DateRange.year(today.year),
        "Last year" to DateRange.year(today.year - 1)
    )

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, preset) ->
                FilterChip(
                    selected = range == preset,
                    onClick = {
                        fromDay = preset.from.toEpochDay()
                        toDay = preset.to.toEpochDay()
                    },
                    label = { Text(label) }
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) {
                Text("From ${Calc.fmtDay(range.from)}")
            }
            OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) {
                Text("To ${Calc.fmtDay(range.to)}")
            }
        }

        if (picking.isNotEmpty()) {
            val initial = if (picking == "from") range.from else range.to
            val state = rememberDatePickerState(
                initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { picking = "" },
                confirmButton = {
                    TextButton(onClick = {
                        val ms = state.selectedDateMillis
                        if (ms != null) {
                            val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            if (picking == "from") {
                                fromDay = day
                                if (toDay < day) toDay = day
                            } else {
                                toDay = day
                                if (fromDay > day) fromDay = day
                            }
                        }
                        picking = ""
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = "" }) { Text("Cancel") } }
            ) { DatePicker(state = state) }
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
                Text("${rangeTrips.size} drives, ${Calc.fmtMiles(rangeTrips.sumOf { it.distanceMeters })}")
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
            val mine = rangeTrips.filter { it.categoryId == cat.id }
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
            onClick = { shareCsv(context, range, trips, categories, rates) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export these dates as spreadsheet (CSV)") }
    }
}

private fun shareCsv(
    context: Context,
    range: DateRange,
    trips: List<TripSummary>,
    categories: List<Category>,
    rates: List<Rate>
) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Remove earlier exports so old copies of the log don't pile up on the phone.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "openmiles-${range.from}-to-${range.to}.csv")
        file.writeText(Csv.buildRange(range, trips, categories, rates))
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
        item {
            AppearanceSection()
            Spacer(Modifier.height(16.dp))
        }
        item {
            AutoDetectSection()
            Spacer(Modifier.height(8.dp))
            DetectionLogSection()
            Spacer(Modifier.height(16.dp))
            ImportSection(vm)
            Spacer(Modifier.height(16.dp))
        }
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
            Text(
                "Version ${appVersion(LocalContext.current)}",
                style = MaterialTheme.typography.bodySmall
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

@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val mode by Appearance.mode.collectAsStateWithLifecycle()
    Text("Appearance", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Appearance.SYSTEM to "Match phone",
            Appearance.LIGHT to "Light",
            Appearance.DARK to "Dark"
        ).forEach { (value, label) ->
            FilterChip(
                selected = mode == value,
                onClick = { Appearance.set(context, value) },
                label = { Text(label) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Optional automatic drive detection (off unless the person turns it on)
// ---------------------------------------------------------------------------

@Composable
private fun AutoDetectSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutoDetect.isEnabled(context)) }
    var missing by remember { mutableStateOf(AutoDetect.missingPermissions(context)) }
    var explainBackground by remember { mutableStateOf(false) }
    var justTurnedOff by remember { mutableStateOf(false) }
    var triedToTurnOn by remember { mutableStateOf(false) }

    fun refresh() {
        missing = AutoDetect.missingPermissions(context)
        enabled = AutoDetect.isEnabled(context)
    }

    // Permissions can change in the phone's Settings while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
                AutoDetect.register(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (AutoDetect.enable(context)) justTurnedOff = false
        refresh()
    }

    val firstLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refresh()
        val fineOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineOk) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            explainBackground = true
        } else if (AutoDetect.enable(context)) {
            justTurnedOff = false
            refresh()
        }
    }

    Text("Automatic drive detection", style = MaterialTheme.typography.titleLarge)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Start and stop drives on their own",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    triedToTurnOn = true
                    if (AutoDetect.enable(context)) {
                        justTurnedOff = false
                        refresh()
                    } else {
                        val perms = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= 29) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        firstLauncher.launch(perms.toTypedArray())
                    }
                } else {
                    AutoDetect.disable(context)
                    triedToTurnOn = false
                    justTurnedOff = true
                    refresh()
                }
            }
        )
    }

    when {
        enabled && missing.isEmpty() -> Text(
            "On. A notification shows whenever a drive is being recorded. " +
                "You can still use Start drive and Stop drive yourself.",
            style = MaterialTheme.typography.bodySmall
        )
        enabled -> {
            Text(
                "Paused. These permissions are turned off: ${missing.joinToString(", ")}.",
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
        triedToTurnOn && missing.isNotEmpty() -> {
            Text(
                "Could not turn on. Still needed: ${missing.joinToString(", ")}. " +
                    "You can turn these on in app settings, under Permissions.",
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
        justTurnedOff -> {
            Text(
                "Off. Nothing runs in the background now. If you like, you can also change " +
                    "Location back to \"Allow only while using the app\" in your phone's settings.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
        else -> Text(
            "Off. Turn this on to record drives without tapping Start. It needs location " +
                "\"Allow all the time\" and Physical activity. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (enabled) {
        Text(
            "Tip: some phones (Samsung especially) stop apps in the background to save battery. " +
                "If drives are missed, open app settings, then Battery, and choose Unrestricted.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (explainBackground) {
        AlertDialog(
            onDismissRequest = { explainBackground = false },
            title = { Text("One more step") },
            text = {
                Text(
                    "Android will open a settings page. Choose \"Allow all the time\" so OpenMiles " +
                        "can notice a drive while the app is closed.\n\n" +
                        "GPS only turns on when a drive is detected, and a notification always shows " +
                        "while it records. Your drives never leave this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainBackground = false
                    if (Build.VERSION.SDK_INT >= 29) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { explainBackground = false }) { Text("Not now") }
            }
        )
    }
}

@Composable
private fun DetectionLogSection() {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Text("Detection log", style = MaterialTheme.typography.titleMedium)
    Text(
        "A private record of when drives started and stopped, and why. Times and events only, " +
            "no locations. It stays on this phone unless you share it.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showing = true }) { Text("View") }
        OutlinedButton(onClick = { shareLog(context) }) { Text("Share") }
        TextButton(onClick = { confirmClear = true }) {
            Text("Clear", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showing) {
        val lines = remember { DiagLog.read(context) }
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("Detection log") },
            text = {
                Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                    if (lines.isEmpty()) {
                        Text("Nothing yet. Events show up here once auto-detect is on or a drive is recorded.")
                    } else {
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("Close") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the log?") },
            text = { Text("This deletes the detection log. Your drives are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    DiagLog.clear(context)
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

private fun shareLog(context: Context) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "openmiles-detection-log.txt")
        val body = DiagLog.read(context, max = 3_000).joinToString("\n")
        file.writeText(DiagLog.header(context) + body.ifEmpty { "(no events yet)" } + "\n")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("OpenMiles detection log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share detection log"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share the log.", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------------------
// Update reminder (offline: it only knows how old this copy is)
// ---------------------------------------------------------------------------

@Composable
private fun UpdateBanner(monthsOld: Long, onCheck: () -> Unit, onLater: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("A newer version of OpenMiles may be available.", fontWeight = FontWeight.Bold)
            Text(
                "This copy is about $monthsOld months old. Checking opens the download page in your browser.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCheck) { Text("Check") }
                TextButton(onClick = onLater) { Text("Not now") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add a drive by hand
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddDriveDialog(
    categories: List<Category>,
    onAdd: (startMs: Long, endMs: Long, miles: Double, categoryId: Long?, notes: String) -> Unit,
    onClose: () -> Unit
) {
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var startMin by rememberSaveable { mutableIntStateOf(9 * 60) }
    var endMin by rememberSaveable { mutableIntStateOf(9 * 60 + 30) }
    var milesText by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf("") } // "", "date", "start", "end"
    var error by remember { mutableStateOf<String?>(null) }

    fun clock(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(java.util.Locale.US, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a drive by hand") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "For drives the app missed. It will be marked \"Added by hand\" in reports.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picking = "date" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: ${Calc.fmtDay(LocalDate.ofEpochDay(day))}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                        Text("Start ${clock(startMin)}")
                    }
                    OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                        Text("End ${clock(endMin)}")
                    }
                }
                OutlinedTextField(
                    value = milesText,
                    onValueChange = { milesText = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                    label = { Text("Miles") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Category (optional)", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = categoryId == cat.id,
                            onClick = { categoryId = if (categoryId == cat.id) null else cat.id },
                            label = { Text(cat.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(AppViewModel.MAX_NOTES) },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val miles = milesText.toDoubleOrNull()
                val date = LocalDate.ofEpochDay(day)
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).plusMinutes(startMin.toLong()).toInstant().toEpochMilli()
                val end = date.atStartOfDay(zone).plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
                error = when {
                    !Validation.isValidManualMiles(miles) ->
                        "Enter the miles, more than 0 and up to ${Validation.MAX_MANUAL_MILES.toInt()}."
                    end <= start -> "The end time must be after the start time."
                    date.isAfter(LocalDate.now()) -> "The date can't be in the future."
                    else -> null
                }
                if (error == null && miles != null) onAdd(start, end, miles, categoryId, notes)
            }) { Text("Add drive") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )

    when (picking) {
        "date" -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = LocalDate.ofEpochDay(day)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { picking = "" },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        }
                        picking = ""
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = "" }) { Text("Cancel") } }
            ) { DatePicker(state = state) }
        }
        "start", "end" -> {
            val current = if (picking == "start") startMin else endMin
            val state = rememberTimePickerState(
                initialHour = current / 60,
                initialMinute = current % 60,
                is24Hour = false
            )
            AlertDialog(
                onDismissRequest = { picking = "" },
                title = { Text(if (picking == "start") "Start time" else "End time") },
                text = { TimeInput(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val value = state.hour * 60 + state.minute
                        if (picking == "start") startMin = value else endMin = value
                        picking = ""
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = "" }) { Text("Cancel") } }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Import from Google Maps Timeline
// ---------------------------------------------------------------------------

@Composable
private fun ImportSection(vm: AppViewModel) {
    val state by vm.importState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readTimeline(uri)
    }
    val busy = state is AppViewModel.ImportState.Reading || state is AppViewModel.ImportState.Saving

    Text("Import drives", style = MaterialTheme.typography.titleLarge)
    Text(
        "Bring in past drives from Google Maps Timeline. First, on your phone open Settings, Location, " +
            "Location services, Timeline, and tap Export Timeline data. Then choose that file here. " +
            "The file is read on this phone only, and only car drives are kept.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(
        onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        enabled = !busy
    ) { Text("Import from Google Timeline") }

    when (val st = state) {
        AppViewModel.ImportState.Reading -> BusyLine("Reading the file. A large file can take a minute.")
        AppViewModel.ImportState.Saving -> BusyLine("Saving drives...")
        is AppViewModel.ImportState.Done -> Text(
            "Added ${st.added} drives. They are on the Drives tab, marked \"Not classified yet\".",
            color = MaterialTheme.colorScheme.primary
        )
        is AppViewModel.ImportState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
        is AppViewModel.ImportState.Ready -> ImportPreviewDialog(
            ready = st,
            onImport = { vm.importDrives(it) },
            onCancel = { vm.clearImport() }
        )
        else -> Unit
    }
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewDialog(
    ready: AppViewModel.ImportState.Ready,
    onImport: (List<ImportedDrive>) -> Unit,
    onCancel: () -> Unit
) {
    val drives = ready.parsed.drives
    val byYear = remember(drives) { drives.groupingBy { it.year() }.eachCount().toSortedMap() }
    var years by remember(drives) { mutableStateOf(byYear.keys.toSet()) }
    var skipShort by remember { mutableStateOf(true) }
    val plan = remember(years, skipShort) {
        TimelineImport.plan(drives, ready.overlapping, skipShort, years)
    }
    val miles = plan.toAdd.sumOf { it.meters } / Calc.METERS_PER_MILE

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import from Timeline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Found ${drives.size} drives. Choose which years to bring in:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    byYear.forEach { (year, count) ->
                        FilterChip(
                            selected = year in years,
                            onClick = { years = if (year in years) years - year else years + year },
                            label = { Text("$year ($count)") }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skipShort, onCheckedChange = { skipShort = it })
                    Text("Skip drives under a quarter mile", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(java.util.Locale.US, "Will add %d drives, %.1f miles.", plan.toAdd.size, miles),
                    fontWeight = FontWeight.Bold
                )
                if (plan.skippedOverlap > 0) {
                    Text("Skipped, already in OpenMiles: ${plan.skippedOverlap}", style = MaterialTheme.typography.bodySmall)
                }
                if (skipShort && plan.skippedShort > 0) {
                    Text("Skipped, under a quarter mile: ${plan.skippedShort}", style = MaterialTheme.typography.bodySmall)
                }
                if (ready.parsed.otherTrips > 0) {
                    Text(
                        "Walks, bike rides and other trips left out: ${ready.parsed.otherTrips}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (ready.parsed.rejected > 0) {
                    Text(
                        "Incomplete entries left out: ${ready.parsed.rejected}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Imported drives start as \"Not classified yet\" and are marked \"Google Timeline\" " +
                        "in reports. Miles are Google's estimate along the roads.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(plan.toAdd) }, enabled = plan.toAdd.isNotEmpty()) {
                Text("Add ${plan.toAdd.size} drives")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private fun appVersion(context: Context): String = try {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    "${info.versionName} (build ${info.versionCode})"
} catch (e: Exception) {
    "unknown"
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open settings.", Toast.LENGTH_LONG).show()
    }
}

private fun openLink(context: Context, url: String) {
    // Only web links are opened, whatever is typed into AppConfig.
    if (!Validation.isSafeWebLink(url)) return
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
    val dateOk = Validation.parseDay(date) != null
    // Accept a comma as the decimal mark too, since some phones use one.
    val amount = dollars.trim().replace(',', '.').toDoubleOrNull()
    val amountOk = amount != null && Validation.isValidRate(amount)
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
