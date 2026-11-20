package com.smarthomebt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val Navy = Color(0xFF080F1D)
private val Panel = Color(0xFF121F32)
private val Blue = Color(0xFF398BFF)
private val Green = Color(0xFF35D07F)
private val Muted = Color(0xFF8795A8)
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartHomeApp() }
    }
}

private class BtController(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var socket: BluetoothSocket? = null
    var connectedName by mutableStateOf<String?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set
    var message by mutableStateOf("Not connected")
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val d = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))
                if (d != null && devices.none { it.address == d.address }) devices = devices + d
            } else if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                scanning = false
            }
        }
    }

    fun bluetoothEnabled(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    @SuppressLint("MissingPermission")
    fun paired(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: run { message = "Bluetooth is unavailable"; return }
        if (!a.isEnabled) { message = "Turn Bluetooth on first"; return }
        devices = paired()
        try {
            if (!scanning) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                scanning = a.startDiscovery()
                message = if (scanning) "Scanning nearby devices…" else "Could not start scan"
            }
        } catch (e: Exception) { message = e.message ?: "Scan failed" }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            try { adapter?.cancelDiscovery() } catch (_: Exception) {}
            socket?.close()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            withContext(Dispatchers.Main) {
                connectedName = device.name ?: "Bluetooth device"
                message = "Connected to $connectedName"
                context.getSharedPreferences("smart_home", Context.MODE_PRIVATE)
                    .edit().putString("last_address", device.address).putString("last_name", connectedName).apply()
            }
            true
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            withContext(Dispatchers.Main) { connectedName = null; message = e.message ?: "Connection failed" }
            false
        }
    }

    fun send(command: Char): Boolean {
        return try {
            val s = socket ?: return false
            s.outputStream.write(command.code)
            s.outputStream.flush()
            true
        } catch (_: IOException) {
            disconnect()
            false
        } catch (_: Exception) { false }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        connectedName = null
        message = "Disconnected"
    }

    @SuppressLint("MissingPermission")
    suspend fun reconnectLast(): Boolean {
        val address = context.getSharedPreferences("smart_home", Context.MODE_PRIVATE)
            .getString("last_address", null) ?: return false
        return try {
            val device = adapter?.getRemoteDevice(address) ?: return false
            connect(device)
        } catch (_: Exception) { false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartHomeApp() {
    val context = LocalContext.current
    val bt = remember { BtController(context) }
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var splash by remember { mutableStateOf(true) }
    var states by remember { mutableStateOf(listOf(false, false, false, false)) }
    var toast by remember { mutableStateOf("") }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = requiredPermissions.all { result[it] == true } }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        splash = false
        hasPermission = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) permissionLauncher.launch(requiredPermissions)
        else scope.launch { bt.reconnectLast() }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, secondary = Green, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            if (splash) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SettingsRemote, null, tint = Blue, modifier = Modifier.size(76.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("SMART HOME BT", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Your home. One touch away.", color = Muted)
                    }
                }
            } else if (!hasPermission) {
                PermissionScreen { permissionLauncher.launch(requiredPermissions) }
            } else if (showDevices || bt.connectedName == null) {
                DeviceScreen(bt, onConnected = { showDevices = false })
            } else {
                Scaffold(
                    containerColor = Navy,
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("SMART HOME BT", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(Green, RoundedCornerShape(50)))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Connected · ${bt.connectedName}", color = Green, fontSize = 11.sp)
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { showDevices = true }) {
                                    Icon(Icons.Default.Bluetooth, null, tint = Blue)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
                        )
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text("CONTROL PANEL", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Your appliances", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Tap a switch to control your room.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(18.dp))
                        val names = listOf("Light 1", "Light 2", "Fan", "Socket")
                        val icons = listOf(Icons.Default.Lightbulb, Icons.Default.Lightbulb, Icons.Default.Refresh, Icons.Default.Power)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(names.indices.toList()) { i ->
                                ApplianceCard(names[i], icons[i], states[i]) {
                                    val next = !states[i]
                                    val commands = listOf('A', 'B', 'C', 'D')
                                    if (bt.send(commands[i])) {
                                        states = states.toMutableList().also { it[i] = next }
                                        toast = "${names[i]} command sent"
                                        val vibrator = if (Build.VERSION.SDK_INT >= 31)
                                            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                                        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                                        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(35, 70))
                                        else @Suppress("DEPRECATION") vibrator.vibrate(35)
                                    } else toast = "Not connected. Connect to HomeAutomation."
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Commands: A · B · C · D", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
        if (toast.isNotBlank()) {
            LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = "" }
            androidx.compose.ui.window.Dialog(onDismissRequest = { toast = "" }) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Text(toast, Modifier.padding(20.dp), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Bluetooth, null, tint = Blue, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(18.dp))
        Text("Bluetooth access required", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Smart Home BT needs Bluetooth access to find and connect to your ESP32. No internet or account is used.",
            color = Muted)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onGrant) { Text("Grant permissions") }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceScreen(bt: BtController, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Connect device", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Choose the paired ESP32 named HomeAutomation.", color = Muted)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, null, tint = if (bt.bluetoothEnabled()) Green else Color.Red)
            Spacer(Modifier.width(8.dp))
            Text(if (bt.bluetoothEnabled()) "Bluetooth is ON" else "Bluetooth is OFF", color = Color.White)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { bt.startScan() }) { Text(if (bt.scanning) "Scanning…" else "Scan") }
        }
        Text(bt.message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        Text("PAIRED DEVICES", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bt.paired().distinctBy { it.address }) { device ->
                DeviceRow(device.name ?: "Unnamed device", device.address, connecting == device.address) {
                    connecting = device.address
                    scope.launch {
                        if (bt.connect(device)) onConnected()
                        connecting = null
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("NEARBY DEVICES", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            items(bt.devices.distinctBy { it.address }) { device ->
                DeviceRow(device.name ?: "Unnamed device", device.address, connecting == device.address) {
                    connecting = device.address
                    scope.launch {
                        if (bt.connect(device)) onConnected()
                        connecting = null
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Tip: pair HomeAutomation in Android Bluetooth settings first. Default ESP32 SPP UUID is used.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun DeviceRow(name: String, address: String, loading: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BluetoothConnected, null, tint = Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(address, color = Muted, fontSize = 11.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("CONNECT", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ApplianceCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isOn: Boolean, onToggle: () -> Unit) {
    val tint by animateColorAsState(if (isOn) Green else Muted, label = "stateColor")
    val scale by animateFloatAsState(if (isOn) 1.03f else 1f, label = "cardScale")
    Card(
        modifier = Modifier.fillMaxWidth().scale(scale),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(if (isOn) Green.copy(alpha = .14f) else Navy, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(tint, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isOn) "ON" else "OFF", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = onToggle, colors = ButtonDefaults.buttonColors(
                containerColor = if (isOn) Green else Blue,
                contentColor = if (isOn) Navy else Color.White
            ), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
                Text(if (isOn) "ON" else "OFF", fontWeight = FontWeight.Bold)
            }
        }
    }
}
