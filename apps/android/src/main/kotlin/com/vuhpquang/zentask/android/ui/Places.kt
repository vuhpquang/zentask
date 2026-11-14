package com.vuhpquang.zentask.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.vuhpquang.zentask.android.LocationReminders
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.model.Place
import com.vuhpquang.zentask.model.Task
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Settings → Places: the saved places location reminders use, and the permission they need. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(app: ZenApp, close: () -> Unit) {
    val context = LocalContext.current
    val revision by app.revision.collectAsState()
    val places = remember(revision) { app.core.placeActions.list() }
    var editing by remember { mutableStateOf<Place?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var fg by remember { mutableStateOf(LocationReminders.hasForeground(context)) }
    var bg by remember { mutableStateOf(LocationReminders.hasBackground(context)) }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fg = LocationReminders.hasForeground(context); bg = LocationReminders.hasBackground(context)
    }
    androidx.activity.compose.BackHandler(onBack = { if (editing != null || creating != null) { editing = null; creating = null } else close() })
    if (editing != null || creating != null) {
        PlaceEditor(app, editing, creating ?: editing!!.kind) { editing = null; creating = null }
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Places") }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Remind about a task when you arrive at or leave a place. Set your places here first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!fg || !bg) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (!fg) "Location is off for ZenTask." else "Location is allowed only while using the app.", color = MaterialTheme.colorScheme.error)
                    Text("Arrive / leave reminders need location \"Allow all the time\". Wi‑Fi names also need location to be read.", style = MaterialTheme.typography.bodySmall)
                    if (!fg) Button(onClick = { askForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
                    else OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open settings → Location → Allow all the time") }
                }
            }
            for (p in places) {
                Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(p.kind), null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.name)
                        Text(buildString {
                            append("${p.radius.toInt()} m")
                            if (p.wifi.isNotEmpty()) append(" · Wi‑Fi " + p.wifi.joinToString(", "))
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (places.none { it.kind == Place.KIND_HOME }) AssistChip(onClick = { creating = Place.KIND_HOME }, label = { Text("Add Home") }, leadingIcon = { Icon(Icons.Filled.Home, null) })
                if (places.none { it.kind == Place.KIND_WORK }) AssistChip(onClick = { creating = Place.KIND_WORK }, label = { Text("Add Work") }, leadingIcon = { Icon(Icons.Filled.Business, null) })
                AssistChip(onClick = { creating = Place.KIND_CUSTOM }, label = { Text("Add place") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
            }
        }
    }
}

fun kindIcon(kind: String) = when (kind) { Place.KIND_HOME -> Icons.Filled.Home; Place.KIND_WORK -> Icons.Filled.Business; else -> Icons.Filled.Place }

/** New or edited place: name, kind, a pin on the map (tap to move it), radius, Wi‑Fi names. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun PlaceEditor(app: ZenApp, editing: Place?, initialKind: String, close: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(editing?.name ?: com.vuhpquang.zentask.tasks.PlaceActions.defaultName(initialKind)) }
    var kind by remember { mutableStateOf(editing?.kind ?: initialKind) }
    var point by remember { mutableStateOf(editing?.let { GeoPoint(it.latitude, it.longitude) }) }
    var radius by remember { mutableStateOf((editing?.radius ?: 150.0).toFloat()) }
    val wifi = remember { mutableStateListOf<String>().apply { addAll(editing?.wifi.orEmpty()) } }
    var wifiText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    // Start on the phone's position for a new place.
    LaunchedEffect(Unit) {
        if (point == null && LocationReminders.hasForeground(context)) {
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null && point == null) point = GeoPoint(loc.latitude, loc.longitude) }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (editing == null) "New place" else "Edit place") },
            navigationIcon = { IconButton(onClick = close) { Icon(Icons.Filled.Close, "Cancel") } },
            actions = {
                TextButton(enabled = point != null, onClick = {
                    val p = point!!
                    if (editing == null) app.core.placeActions.create(name, kind, p.latitude, p.longitude, radius.toDouble(), wifi.toList())
                    else app.core.placeActions.update(editing, name, kind, p.latitude, p.longitude, radius.toDouble(), wifi.toList())
                    close()
                }) { Text("Save") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Place.KIND_HOME to "Home", Place.KIND_WORK to "Work", Place.KIND_CUSTOM to "Other").forEachIndexed { i, (k, label) ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Text("Tap the map to set the place, then check the circle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))) {
                PlaceMap(point, radius.toDouble()) { point = it }
            }
            OutlinedButton(onClick = {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc -> if (loc != null) point = GeoPoint(loc.latitude, loc.longitude) }
            }, enabled = LocationReminders.hasForeground(context)) { Icon(Icons.Filled.MyLocation, null); Text("  Use where I am") }
            Text("Radius: ${radius.toInt()} m")
            Slider(radius, { radius = it }, valueRange = 50f..1000f)
            Text("Wi‑Fi here (connecting to it also counts as arriving)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in wifi) InputChip(selected = false, onClick = { wifi.remove(w) }, label = { Text(w) }, trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.padding(0.dp)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(wifiText, { wifiText = it }, label = { Text("Wi‑Fi name") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { wifiText.trim().takeIf { it.isNotEmpty() && it !in wifi }?.let { wifi.add(it) }; wifiText = "" }) { Text("Add") }
            }
            LocationReminders.currentSsid(context)?.takeIf { it !in wifi }?.let { ssid ->
                AssistChip(onClick = { wifi.add(ssid) }, label = { Text("Add current Wi‑Fi: $ssid") }, leadingIcon = { Icon(Icons.Filled.Wifi, null) })
            }
            if (editing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete place", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete && editing != null) {
        Confirm("Delete \"${editing.name}\"?", "Tasks that remind there lose their location reminder.", "Delete",
            onConfirm = { app.core.placeActions.delete(editing.id); confirmDelete = false; close() }, onDismiss = { confirmDelete = false })
    }
}

/** OpenStreetMap (osmdroid): tap to move the pin; the circle shows the radius. */
@Composable
private fun PlaceMap(point: GeoPoint?, radius: Double, pick: (GeoPoint) -> Unit) {
    val context = LocalContext.current
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(point ?: GeoPoint(10.7769, 106.7009)) // Saigon until the phone's position arrives
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pick(p); return true }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
            // Let the map, not the scrolling page, take drags.
            setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause() } }
    var centered by remember { mutableStateOf(point != null) }
    AndroidView({ map }, Modifier.fillMaxWidth().height(300.dp)) { m ->
        m.overlays.removeAll { it is Marker || it is Polygon }
        if (point != null) {
            if (!centered) { m.controller.setCenter(point); centered = true }
            m.overlays.add(Polygon(m).apply { points = Polygon.pointsAsCircle(point, radius); fillPaint.color = 0x335B5FE9; outlinePaint.color = 0xFF5B5FE9.toInt(); outlinePaint.strokeWidth = 3f })
            m.overlays.add(Marker(m).apply { position = point; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        }
        m.invalidate()
    }
}

/** Task detail chip: remind when arriving at / leaving a saved place. */
@Composable
fun LocationChip(app: ZenApp, task: Task, expanded: Boolean, open: () -> Unit, close: () -> Unit, managePlaces: () -> Unit) {
    val places = remember(expanded) { app.core.placeActions.list() }
    val current = places.firstOrNull { it.id == task.placeId }
    var trigger by remember(task.placeTrigger) { mutableStateOf(task.placeTrigger ?: Place.ARRIVE) }
    Box {
        AssistChip(onClick = open, label = { Text(current?.let { LocationReminders.triggerTitle(task.placeTrigger) + " " + it.name } ?: "Location", maxLines = 1) },
            leadingIcon = { Icon(current?.let { kindIcon(it.kind) } ?: Icons.Filled.Place, null) })
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = trigger == Place.ARRIVE, onClick = { trigger = Place.ARRIVE }, label = { Text("When I arrive") })
                FilterChip(selected = trigger == Place.LEAVE, onClick = { trigger = Place.LEAVE }, label = { Text("When I leave") })
            }
            for (p in places) {
                DropdownMenuItem(text = { Text(p.name) }, leadingIcon = { Icon(kindIcon(p.kind), null) },
                    onClick = { app.core.actions.setLocationReminder(task.id, p.id, trigger); close() })
            }
            if (places.isEmpty()) DropdownMenuItem(text = { Text("No places yet") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("Manage places…") }, onClick = { close(); managePlaces() })
            if (current != null) DropdownMenuItem(text = { Text("No location reminder") }, onClick = { app.core.actions.setLocationReminder(task.id, null, null); close() })
        }
    }
}
