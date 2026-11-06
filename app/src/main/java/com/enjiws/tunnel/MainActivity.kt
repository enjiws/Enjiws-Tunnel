package com.enjiws.tunnel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme

private val Forest = Color(0xFF10271F)
private val ForestDeep = Color(0xFF071510)
private val Mint = Color(0xFF78D6A6)
private val Panel = Color(0xFF18372B)
private val Muted = Color(0xFF9CB5A7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EnjiwsTunnelApp() }
    }
}

@Composable
private fun EnjiwsTunnelApp() {
    val colors = darkColorScheme(
        primary = Mint,
        onPrimary = ForestDeep,
        background = ForestDeep,
        surface = Forest,
        onBackground = Color(0xFFEAF4EE),
        onSurface = Color(0xFFEAF4EE),
        secondary = Muted
    )

    MaterialTheme(colorScheme = colors) {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Scaffold(
            containerColor = ForestDeep,
            bottomBar = {
                NavigationBar(containerColor = Forest) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = "Profiles") },
                        label = { Text("Profiles") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { innerPadding ->
            when (selectedTab) {
                0 -> HomeScreen(Modifier.padding(innerPadding))
                1 -> PlaceholderScreen(
                    title = "Connection profiles",
                    description = "Your saved SSH, HTTP, and TLS profiles will appear here.",
                    modifier = Modifier.padding(innerPadding)
                )
                else -> PlaceholderScreen(
                    title = "Settings",
                    description = "Performance, DNS, routing, and app preferences will live here.",
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ForestDeep)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ENJIWS",
                color = Mint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Text(
                text = "Tunnel",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Fast by design. Stable by engineering.",
                color = Muted,
                fontSize = 13.sp
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(12.dp).background(Mint, CircleShape)
                )
                Text(
                    text = "NOT CONNECTED",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "VPN engine is not installed yet",
                    color = Muted,
                    fontSize = 12.sp
                )
                Surface(
                    color = ForestDeep,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "CORE SETUP IN PROGRESS",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        color = Mint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Text(
            text = "PERFORMANCE OVERVIEW",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Latency", "--", "Not measured", Modifier.weight(1f))
            MetricCard("Traffic", "--", "No active tunnel", Modifier.weight(1f))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Forest)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("PERFORMANCE FIRST", color = Mint, fontWeight = FontWeight.Bold)
                Text(
                    "Low overhead, careful reconnects, and battery-aware background work.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = "Enjiws Tunnel  •  V0.1.0",
            modifier = Modifier.fillMaxWidth(),
            color = Muted.copy(alpha = 0.65f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Forest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().background(ForestDeep).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(description, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(18.dp))
        Text("Coming in a future build", color = Mint, fontSize = 12.sp)
    }
}
