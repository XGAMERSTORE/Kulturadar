package cz.kulturadar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class XgPublishedApp(
    val name: String,
    val subtitle: String,
    val packageName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val xgPublishedApps = listOf(
    XgPublishedApp("IP Lookup", "IP, Wi‑Fi a LAN nástroje", "com.xgiplookup", Icons.Default.NetworkCheck),
    XgPublishedApp("LumaFiles", "Správce souborů a úložiště", "com.xg.lumafiles", Icons.Default.Folder),
    XgPublishedApp("LumaForge Studio", "Grafika a úprava obrázků", "cz.lumaforge.studio", Icons.Default.Code),
    XgPublishedApp("LumaReader", "Čtečka e‑knih", "com.xgamerstore.lumareader", Icons.Default.MenuBook)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KulturadarWithApps() {
    var showApps by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        KulturadarProApp()

        SmallFloatingActionButton(
            onClick = { showApps = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 92.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Apps, contentDescription = "Další aplikace XG")
        }
    }

    if (showApps) {
        XgAppsSheet(onDismiss = { showApps = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XgAppsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Další aplikace XG", fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(
                    "Aplikace vydané pod XG Applications na Google Play.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            xgPublishedApps.forEach { app ->
                item(key = app.packageName) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openPlay(context, "https://play.google.com/store/apps/details?id=${app.packageName}")
                            },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(app.icon, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(app.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        openPlay(context, "https://play.google.com/store/apps/developer?id=XG+Applications")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Všechny aplikace XG Applications")
                }
            }
        }
    }
}

private fun openPlay(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
