package com.tcl.launcher2

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

private val Neon = Color(0xFFA855F7)
private val NeonSoft = Color(0xFFC084FC)
private val Bg = Color(0xFF070A14)
private val Panel = Color(0xCC0C0F20)
private val Muted = Color(0xFFAAB0C8)
private val Shape28 = RoundedCornerShape(28.dp)

// ---- Dados ----------------------------------------------------------------
data class AppItem(val label: String, val pkg: String, val intent: Intent,
                   val banner: ImageBitmap?, val icon: ImageBitmap, val version: String)

data class Stats(val ramUsed: Long, val ramTotal: Long, val stUsed: Long, val stTotal: Long, val net: String)
data class Weather(val temp: Int, val desc: String, val icon: ImageVector)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val ph = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(tv, 0) + pm.queryIntentActivities(ph, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            val ai = ri.activityInfo
            val launch = Intent(Intent.ACTION_MAIN).setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ver = try { pm.getPackageInfo(ai.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            AppItem(
                ri.loadLabel(pm).toString(), ai.packageName, launch,
                ai.applicationInfo.loadBanner(pm)?.toBitmap(480, 270)?.asImageBitmap(),
                ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(), ver
            )
        }.sortedBy { it.label.lowercase() }
}

fun readStats(ctx: Context): Stats {
    val mi = ActivityManager.MemoryInfo()
    ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
    val sf = StatFs(Environment.getDataDirectory().path)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val net = when {
        caps == null -> "Sem conexão"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi conectado"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet conectada"
        else -> "Conectado"
    }
    return Stats(mi.totalMem - mi.availMem, mi.totalMem,
        sf.totalBytes - sf.availableBytes, sf.totalBytes, net)
}

// São Paulo por padrão. Troque latitude/longitude para outra cidade.
suspend fun fetchWeather(): Weather? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=-23.55&longitude=-46.63&current=temperature_2m,weather_code")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        val j = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("current")
        val code = j.getInt("weather_code")
        val (d, i) = when (code) {
            0 -> "Céu limpo" to Icons.Rounded.WbSunny
            1, 2 -> "Parcialmente nublado" to Icons.Rounded.WbCloudy
            45, 48 -> "Neblina" to Icons.Rounded.Cloud
            in 51..67, in 80..82 -> "Chuva" to Icons.Rounded.WaterDrop
            in 95..99 -> "Tempestade" to Icons.Rounded.Thunderstorm
            else -> "Nublado" to Icons.Rounded.Cloud
        }
        Weather(j.getDouble("temperature_2m").roundToInt(), d, i)
    } catch (e: Exception) { null }
}

private fun gb(b: Long) = String.format(Locale("pt", "BR"), "%.1f GB", b / 1073741824.0)

// ---- Activity ---------------------------------------------------------------
class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackHandler {}; Launcher(tick) }
    }
    override fun onResume() { super.onResume(); tick++ }
}

// ---- UI ---------------------------------------------------------------------
@Composable
fun Launcher(tick: Int) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var selected by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    LaunchedEffect(tick) { while (true) { stats = readStats(ctx); delay(5000) } }
    LaunchedEffect(Unit) { while (true) { weather = fetchWeather() ?: weather; delay(900_000) } }

    Box(Modifier.fillMaxSize().background(
        Brush.radialGradient(listOf(Color(0xFF1B1250), Color(0xFF0A0D1F), Bg), radius = 1400f))) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(ctx)
            AppList(apps, selected, { selected = it }, ctx)
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { Hero(apps.getOrNull(selected), ctx) }
                Widgets(weather, stats)
            }
        }
    }
}

@Composable
fun Sidebar(ctx: Context) {
    Column(Modifier.width(76.dp).fillMaxHeight().background(Color(0xCC080A16)).padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NavIcon(Icons.Rounded.Home, "Início", active = true) {}
        NavIcon(Icons.Rounded.Settings, "Configurações", active = false) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun NavIcon(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = active || focused
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
        .background(if (lit) Brush.linearGradient(listOf(Color(0xFF8B3DFF), NeonSoft)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
        .onFocusChanged { focused = it.isFocused }.clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (lit) Color.White else Color(0xFF8D93AD), modifier = Modifier.size(26.dp))
    }
}

@Composable
fun AppList(apps: List<AppItem>, selected: Int, onSelect: (Int) -> Unit, ctx: Context) {
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) try { first.requestFocus() } catch (_: Exception) {} }
    Column(Modifier.padding(vertical = 24.dp, horizontal = 12.dp).width(320.dp).fillMaxHeight()
        .clip(Shape28).background(Panel).border(1.dp, Neon.copy(.2f), Shape28).padding(12.dp)) {
        Text("APLICATIVOS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                val sel = i == selected
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                    .then(if (sel) Modifier.background(Brush.horizontalGradient(listOf(Neon.copy(.35f), Neon.copy(.1f))))
                        .border(1.5.dp, Color(0xFFB266FF), RoundedCornerShape(16.dp)) else Modifier)
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) onSelect(i) }
                    .clickable { ctx.startActivity(app.intent) }
                    .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(app.icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    Text(app.label, maxLines = 1, color = if (sel) Color.White else Color(0xFFB9BFD6), fontSize = 17.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun Hero(app: AppItem?, ctx: Context) {
    Box(Modifier.fillMaxSize().clip(Shape28)
        .background(Brush.linearGradient(listOf(Color(0xFF12082E), Color(0xFF2A1066), Color(0xFF6D28D9))))
        .border(1.dp, Neon.copy(.3f), Shape28)) {
        if (app == null) return@Box
        if (app.banner != null) Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Image(app.icon, null, Modifier.align(Alignment.CenterEnd).padding(end = 80.dp).size(150.dp))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            listOf(Color(0xEB05070F), Color(0x8C05070F), Color.Transparent))))
        Column(Modifier.align(Alignment.BottomStart).padding(36.dp).width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("APLICATIVO", color = Color(0xFFD8B4FE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Text(app.label, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(app.pkg, color = Color(0xFFD6D9EA), fontSize = 14.sp, maxLines = 1)
            if (app.version.isNotBlank()) Text("Versão ${app.version}", color = Color(0xFFD6D9EA), fontSize = 14.sp)
            Box(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50))
                .background(Brush.linearGradient(listOf(Color(0xFF8B3DFF), NeonSoft)))
                .padding(horizontal = 40.dp, vertical = 12.dp)) {
                Text("ABRIR", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun Widgets(w: Weather?, s: Stats?) {
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Widget("CLIMA", w?.icon ?: Icons.Rounded.Cloud, w?.let { "${it.temp}°C" } ?: "--", w?.desc ?: "Sem dados", null)
        Widget("USO DE RAM", Icons.Rounded.Memory, s?.let { gb(it.ramUsed) } ?: "--",
            s?.let { "${gb(it.ramTotal - it.ramUsed)} livres de ${gb(it.ramTotal)}" } ?: "", s?.let { it.ramUsed.toFloat() / it.ramTotal })
        Widget("ARMAZENAMENTO", Icons.Rounded.Storage, s?.let { gb(it.stUsed) } ?: "--",
            s?.let { "${gb(it.stTotal - it.stUsed)} livres de ${gb(it.stTotal)}" } ?: "", s?.let { it.stUsed.toFloat() / it.stTotal })
        Widget("SISTEMA", Icons.Rounded.Wifi, s?.net ?: "--", "${Build.MODEL} · Android TV ${Build.VERSION.RELEASE}", null, 20)
    }
}

@Composable
fun RowScope.Widget(title: String, icon: ImageVector, big: String, small: String, progress: Float?, bigSize: Int = 30) {
    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Panel)
        .border(1.dp, Neon.copy(.22f), RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color(0xFFA99BD6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Icon(icon, null, tint = NeonSoft, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(big, color = Color.White, fontSize = bigSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (progress != null) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(.1f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF7C3AED), NeonSoft))))
            }
        }
        Text(small, color = Muted, fontSize = 12.sp, maxLines = 2)
    }
}
