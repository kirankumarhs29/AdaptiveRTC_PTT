package net.sense.mesh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api

private enum class WalkieTab { Walkie, Nearby, Messages }

@Composable
fun MeshHomeScreen(viewModel: MeshViewModel, onExportLogs: (() -> Unit)? = null) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(WalkieTab.Walkie) }

    Surface(color = Color(0xFF050712)) {
        Column(modifier = Modifier.fillMaxSize()) {
            WalkieTopBar(
                localId = uiState.localNodeId,
                connectionState = uiState.connectionState,
                voiceState = uiState.voice,
                onExportLogs = onExportLogs
            )

            when (tab) {
                WalkieTab.Walkie -> WalkieHomeScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenNearby = { tab = WalkieTab.Nearby },
                    onOpenMessages = { tab = WalkieTab.Messages },
                    onExportLogs = onExportLogs
                )
                WalkieTab.Nearby -> NearbyScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenRadio = { tab = WalkieTab.Walkie }
                )
                WalkieTab.Messages -> MessageScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenRadio = { tab = WalkieTab.Walkie }
                )
            }

            WalkieBottomNav(selected = tab, onSelect = { tab = it })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalkieTopBar(
    localId: String,
    connectionState: ConnectionState,
    voiceState: VoiceUiState,
    onExportLogs: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("NetSense Field Radio", color = Color(0xFFF9FAFB), style = MaterialTheme.typography.titleMedium)
                Text(
                    localId.ifBlank { "initializing…" }.let { "ID: $it" },
                    color = Color(0xFF9CA3AF),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF020617),
            titleContentColor = Color(0xFFF9FAFB)
        )
    )
}

@Composable
private fun WalkieBottomNav(selected: WalkieTab, onSelect: (WalkieTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF020617))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Radio tab
        TabItem(
            label = "Radio",
            selected = selected == WalkieTab.Walkie,
            onClick = { onSelect(WalkieTab.Walkie) },
            modifier = Modifier.weight(1f)
        )
        // Nearby tab
        TabItem(
            label = "Nearby",
            selected = selected == WalkieTab.Nearby,
            onClick = { onSelect(WalkieTab.Nearby) },
            modifier = Modifier.weight(1f)
        )
        // Messages tab
        TabItem(
            label = "Messages",
            selected = selected == WalkieTab.Messages,
            onClick = { onSelect(WalkieTab.Messages) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF2563EB) else Color(0xFF1F2937),
            contentColor = if (selected) Color(0xFFF9FAFB) else Color(0xFF9CA3AF)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (selected) 2.dp else 0.dp
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WalkieHomeScreen(
    uiState: MeshUiState,
    viewModel: MeshViewModel,
    onOpenNearby: () -> Unit,
    onOpenMessages: () -> Unit,
    onExportLogs: (() -> Unit)? = null
) {
    val voice = uiState.voice
    val activePeer = uiState.peers.firstOrNull { it.state == ConnectionState.Connected }
    
    val readyForPtt = voice.phase == VoicePhase.Ready || voice.phase == VoicePhase.Streaming
    
    // Determine main call state message for the user (not technical jargon)
    val callStateMsg = when {
        voice.remoteTransmitting -> "Peer is talking"
        else -> when (voice.phase) {
            VoicePhase.Idle -> if (activePeer != null) "Ready to call" else "No contact selected"
            VoicePhase.Discovering -> "Searching for peers"
            VoicePhase.Connecting -> "Connecting…"
            VoicePhase.Connected -> "Link ready"
            VoicePhase.AwaitingAccept, VoicePhase.Preparing -> "Preparing connection…"
            VoicePhase.Ready -> "Ready to talk"
            VoicePhase.Streaming -> "Talking…"
            VoicePhase.Ending -> "Ending…"
            VoicePhase.Error -> "Connection issue"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // TOP: Contact info section
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (activePeer == null) {
                // No contact selected state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.4f))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "No Contact Selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFF9FAFB)
                    )
                    Text(
                        "Choose a peer from Nearby to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9CA3AF),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                // Contact selected state - show prominent peer name and signal
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        activePeer.name,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color(0xFFF9FAFB)
                    )
                    
                    // Signal quality indicator - cleaner, less technical
                    val signalLabel = when {
                        activePeer.rssi >= -55 -> "Excellent signal"
                        activePeer.rssi >= -70 -> "Good signal"
                        activePeer.rssi >= -85 -> "Fair signal"
                        else -> "Weak signal"
                    }
                    val signalBars = when {
                        activePeer.rssi >= -55 -> 4
                        activePeer.rssi >= -70 -> 3
                        activePeer.rssi >= -85 -> 2
                        else -> 1
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            signalLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF)
                        )
                        SignalBars(bars = signalBars, color = Color(0xFF3B82F6))
                    }
                }
            }
            
            // Status pill - simplified
            CallStatusIndicator(phase = voice.phase, remoteTransmitting = voice.remoteTransmitting)
        }

        // MIDDLE: Large PTT button centered
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WalkiePttButton(
                enabled = readyForPtt && !voice.remoteTransmitting,
                peerTalking = voice.remoteTransmitting,
                talking = voice.pushToTalkActive,
                onStart = { viewModel.startPushToTalk() },
                onStop = { viewModel.stopPushToTalk() }
            )

            Spacer(Modifier.height(24.dp))
            
            // Call state message
            Text(
                callStateMsg,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFC7D2E0),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // BOTTOM: Action buttons in clear hierarchy
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (activePeer != null) {
                // Primary action: Call control
                val (callButtonText, callButtonEnabled, callAction) = when (voice.phase) {
                    VoicePhase.Idle, VoicePhase.Connected -> Triple("Start Call", true, { viewModel.startVoiceCall() })
                    VoicePhase.Ready, VoicePhase.Streaming, VoicePhase.AwaitingAccept, VoicePhase.Preparing -> 
                        Triple("End Call", true, { viewModel.endVoiceCall() })
                    else -> Triple("Connecting…", false, {})
                }
                
                Button(
                    onClick = callAction,
                    enabled = callButtonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        disabledContainerColor = Color(0xFF1F2937)
                    )
                ) {
                    Text(callButtonText, modifier = Modifier.padding(vertical = 8.dp))
                }

                // Secondary actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenMessages,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Message")
                    }
                    OutlinedButton(
                        onClick = { viewModel.disconnect(activePeer.id) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
            } else {
                // No peer: show discovery button
                Button(
                    onClick = {
                        viewModel.startDiscovery()
                        onOpenNearby()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Find Nearby", modifier = Modifier.padding(vertical = 8.dp))
                }
                
                OutlinedButton(
                    onClick = onOpenNearby,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Browse Peers")
                }
            }

            if (onExportLogs != null) {
                OutlinedButton(
                    onClick = onExportLogs,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0xFF374151))
                ) {
                    Text("Debug: Export Logs", color = Color(0xFF9CA3AF))
                }
            }
        }
    }
}

@Composable
private fun WalkiePttButton(
    enabled: Boolean,
    talking: Boolean,
    peerTalking: Boolean = false,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // Cleaner color logic - primary states only
    val buttonColor = when {
        peerTalking -> Color(0xFFF59E0B)  // Amber: peer speaking
        talking -> Color(0xFFDC2626)      // Red: user speaking  
        !enabled -> Color(0xFF374151)     // Disabled: gray
        else -> Color(0xFF10B981)         // Enabled: green
    }
    
    val highlightColor = when {
        peerTalking -> Color(0xFFFDE68A)
        talking -> Color(0xFFFCA5A5)
        else -> Color(0xFFA7F3D0)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed, enabled) {
        if (!enabled) {
            onStop()
        } else if (pressed) {
            onStart()
        } else {
            onStop()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer glow when talking or peer talking  
            if (talking || peerTalking) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(highlightColor.copy(alpha = 0.25f))
                )
            }

            // Main button
            Button(
                onClick = { },
                enabled = enabled,
                interactionSource = interactionSource,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    disabledContainerColor = Color(0xFF1F2937),
                    contentColor = Color(0xFFFAFAFA)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (pressed) 2.dp else 4.dp,
                    pressedElevation = 0.dp
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = when {
                            !enabled -> "Unavailable"
                            talking -> "Talking…"
                            peerTalking -> "Listening…"
                            else -> "Hold"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                }
            }
        }
        
        // Instructions text
        if (enabled && !talking && !peerTalking) {
            Text(
                "Press and hold to transmit",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

@Composable
private fun CallStatusIndicator(
    phase: VoicePhase,
    remoteTransmitting: Boolean
) {
    val (text, backgroundColor, textColor) = when {
        remoteTransmitting -> Triple("Peer is talking", Color(0xFFF59E0B).copy(alpha = 0.15f), Color(0xFFFCD34D))
        phase == VoicePhase.Streaming -> Triple("You are talking", Color(0xFFDC2626).copy(alpha = 0.15f), Color(0xFFFCA5A5))
        phase == VoicePhase.Ready -> Triple("Ready to talk", Color(0xFF22C55E).copy(alpha = 0.15f), Color(0xFF86EFAC))
        phase == VoicePhase.Connected -> Triple("Link ready", Color(0xFF0FA3B1).copy(alpha = 0.15f), Color(0xFF67E8F9))
        phase == VoicePhase.Connecting || phase == VoicePhase.Preparing || phase == VoicePhase.AwaitingAccept ->
            Triple("Connecting…", Color(0xFFF59E0B).copy(alpha = 0.15f), Color(0xFFFCD34D))
        phase == VoicePhase.Error -> Triple("Connection error", Color(0xFFEF4444).copy(alpha = 0.15f), Color(0xFFFCA5A5))
        else -> Triple("No call active", Color(0xFF4B5563).copy(alpha = 0.15f), Color(0xFFC7D2E0))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor
        )
    }
}

@Composable
private fun WalkieStatusPill(
    text: String,
    phase: VoicePhase,
    connected: Boolean
) {
    val color = when {
        phase == VoicePhase.Streaming -> Color(0xFFDC2626)
        phase == VoicePhase.Ready -> Color(0xFF22C55E)
        phase == VoicePhase.Connecting || phase == VoicePhase.Preparing || phase == VoicePhase.AwaitingAccept ->
            Color(0xFFF59E0B)
        phase == VoicePhase.Error -> Color(0xFFEF4444)
        else -> Color(0xFF4B5563)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFF9FAFB)
        )
        if (connected) {
            Spacer(Modifier.width(4.dp))
            Text(
                "· Linked",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBBF7D0)
            )
        }
    }
}

@Composable
private fun NearbyScreen(
    uiState: MeshUiState,
    viewModel: MeshViewModel,
    onOpenRadio: () -> Unit
) {
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.connectionState, uiState.peers.size) {
        if (!autoStarted && uiState.peers.isEmpty() && uiState.connectionState != ConnectionState.Discovering) {
            autoStarted = true
            viewModel.startDiscovery()
        }
    }

    var navigatedAfterLink by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.peers) {
        val hasConnected = uiState.peers.any { it.state == ConnectionState.Connected }
        if (!navigatedAfterLink && hasConnected) {
            navigatedAfterLink = true
            onOpenRadio()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with controls
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Available Peers",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFF9FAFB)
                )
                Text(
                    "${uiState.peers.size} nearby",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9CA3AF)
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.startDiscovery() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text(if (uiState.connectionState == ConnectionState.Discovering) "Scanning…" else "Scan")
                }
                OutlinedButton(
                    onClick = { viewModel.stopDiscovery() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }
        }

        // Peer list or empty state
        if (uiState.peers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No peers found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFF9FAFB),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ensure the app is open on nearby devices and try scanning again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.peers, key = { it.id }) { peer ->
                    NearbyPeerTile(peer = peer, onConnect = { viewModel.connect(peer.id) })
                }
            }
        }
        
        // Footer
        OutlinedButton(
            onClick = onOpenRadio,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Radio")
        }
    }
}

@Composable
private fun NearbyPeerTile(peer: Node, onConnect: () -> Unit) {
    val signalBars = when {
        peer.rssi >= -55 -> 4
        peer.rssi >= -70 -> 3
        peer.rssi >= -85 -> 2
        else -> 1
    }
    val signalLabel = when {
        peer.rssi >= -55 -> "Excellent"
        peer.rssi >= -70 -> "Good"
        peer.rssi >= -85 -> "Fair"
        else -> "Weak"
    }
    
    val stateColor = when (peer.state) {
        ConnectionState.Connected -> Color(0xFF10B981)
        ConnectionState.Connecting -> Color(0xFFF59E0B)
        ConnectionState.Discovering -> Color(0xFF3B82F6)
        ConnectionState.Error -> Color(0xFFEF4444)
        ConnectionState.Disconnected -> Color(0xFF6B7280)
    }
    val stateLabel = when (peer.state) {
        ConnectionState.Connected -> "Connected"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Discovering -> "Discovering…"
        ConnectionState.Error -> "Error"
        ConnectionState.Disconnected -> "Available"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Peer name and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        peer.name,
                        color = Color(0xFFF9FAFB),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SignalBars(bars = signalBars, color = Color(0xFF3B82F6))
                        Text(
                            signalLabel,
                            color = Color(0xFF9CA3AF),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(stateColor.copy(alpha = 0.16f))
                        .border(1.dp, stateColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Text(
                        stateLabel,
                        color = stateColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Action button
            Button(
                onClick = onConnect,
                enabled = peer.state == ConnectionState.Disconnected || peer.state == ConnectionState.Discovering,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                    disabledContainerColor = Color(0xFF1F2937)
                )
            ) {
                Text(
                    when (peer.state) {
                        ConnectionState.Connected -> "Already Connected"
                        ConnectionState.Connecting -> "Connecting…"
                        else -> "Connect"
                    },
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SignalBars(bars: Int, color: Color = Color(0xFF22C55E)) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { index ->
            val active = index < bars
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(6.dp + (index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) color
                        else Color(0xFF1F2937)
                    )
            )
        }
    }
}

@Composable
private fun MessageScreen(
    uiState: MeshUiState,
    viewModel: MeshViewModel,
    onOpenRadio: () -> Unit
) {
    val activePeer = uiState.peers.firstOrNull { it.state == ConnectionState.Connected }
    val peerId = activePeer?.id
    val messages = peerId?.let { uiState.chatHistory[it] }.orEmpty()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activePeer != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        activePeer.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFFF9FAFB)
                    )
                    Text(
                        "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981)
                    )
                }
            } else {
                Text(
                    "No Contact Connected",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFF9FAFB)
                )
            }
            OutlinedButton(onClick = onOpenRadio, modifier = Modifier.height(36.dp)) {
                Text("Radio", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (activePeer == null) {
            // No peer connected state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No contact connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFF9FAFB)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connect to a peer from the Radio tab to message them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
            // Message list
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No messages yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFC7D2E0)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Start a conversation with ${activePeer.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9CA3AF)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        // Message input
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message", color = Color(0xFF6B7280)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(6.dp)
            )
            Button(
                onClick = {
                    val id = peerId
                    if (!draft.isBlank() && id != null) {
                        viewModel.sendMessage(id, draft)
                        draft = ""
                    }
                },
                enabled = !draft.isBlank() && peerId != null,
                modifier = Modifier.height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Send", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatLogEntry) {
    val (bubbleColor, bubbleAccent) = when (message.direction) {
        ChatDirection.Incoming -> Pair(Color(0xFF164E63), Color(0xFF06B6D4))
        ChatDirection.Outgoing -> Pair(Color(0xFF1E3A8A), Color(0xFF3B82F6))
        ChatDirection.System -> Pair(Color(0xFF1E293B), Color(0xFF94A3B8))
    }
    val isOutgoing = message.direction == ChatDirection.Outgoing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(10.dp))
                .background(bubbleColor)
                .border(0.5.dp, bubbleAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                message.text,
                color = Color(0xFFF1F5F9),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                prettyTime(message.timestampEpochMs),
                color = bubbleAccent.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun prettyTime(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d".format(dt.hour, dt.minute)
}
