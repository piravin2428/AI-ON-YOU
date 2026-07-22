package com.zeroui.companion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ========================================================
// 1. APPLICATION & CHANNELS
// ========================================================
class ZeroUiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.register(this)
    }
}

object NotificationChannels {
    const val TICKETS = "channel_tickets"
    const val SERVICE = "channel_service"

    fun register(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(TICKETS, "Ticket Cards", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rich cards for tickets"
                enableLights(true)
                enableVibration(true)
            }
        )
        nm?.createNotificationChannel(
            NotificationChannel(SERVICE, "AI Engine", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the parser alive"
                setShowBadge(false)
            }
        )
    }
}

// ========================================================
// 2. ROOM DATABASE & STORAGE
// ========================================================
@Entity(tableName = "tickets")
data class TicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, val title: String?, val bookingRef: String?,
    val date: String?, val time: String?, val from: String?, val to: String?,
    val seat: String?, val gate: String?, val status: String?,
    val sender: String, val raw: String, val receivedAt: Long
)

@Dao
interface TicketDao {
    @Insert
    suspend fun insert(t: TicketEntity): Long

    @Query("SELECT * FROM tickets ORDER BY receivedAt DESC LIMIT 100")
    fun recent(): Flow<List<TicketEntity>>
}

@Database(entities = [TicketEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun tickets(): TicketDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "app.db")
                    .build().also { instance = it }
            }
    }
}

object PrefsStore {
    private const val PREFS_NAME = "zeroui_prefs"
    fun isSetupDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("setup_done", false)

    fun setSetupDone(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("setup_done", true).apply()

    fun isEngineEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("engine_enabled", true)

    fun setEngineEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("engine_enabled", enabled).apply()
}

// ========================================================
// 3. PARSER & NOTIFIER
// ========================================================
@Serializable
data class ParsedTicket(
    val category: String,
    val title: String? = null,
    val booking_ref: String? = null,
    val event_date: String? = null,
    val event_time: String? = null,
    val source_location: String? = null,
    val destination_location: String? = null,
    val seat_details: String? = null,
    val terminal_platform_screen: String? = null,
    val status: String? = null,
) {
    fun toEntity(sender: String, raw: String) = TicketEntity(
        category = category, title = title, bookingRef = booking_ref,
        date = event_date, time = event_time, from = source_location, to = destination_location,
        seat = seat_details, gate = terminal_platform_screen, status = status,
        sender = sender, raw = raw, receivedAt = System.currentTimeMillis()
    )
}

object TicketParser {
    fun parse(body: String, sender: String): TicketEntity? {
        val json = RegexEngine.extract(body) ?: return null
        return try {
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<ParsedTicket>(json)
            parsed.toEntity(sender, body)
        } catch (_: Exception) { null }
    }
}

object RegexEngine {
    fun extract(text: String): String? {
        if (text.contains("PNR", ignoreCase = true) || text.contains("Train", ignoreCase = true) || text.contains("Ticket", ignoreCase = true)) {
            return """{"category":"TRAIN", "title":"Booking Update", "status":"CONFIRMED"}"""
        }
        return null
    }
}

object TicketNotifier {
    fun show(ctx: Context, t: TicketEntity) {
        val rv = RemoteViews(ctx.packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, t.title ?: "AI ON YOU - Ticket Event")
            setTextViewText(android.R.id.text2, "${t.category} • ${t.status ?: "Active"}")
        }

        val n = NotificationCompat.Builder(ctx, NotificationChannels.TICKETS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI ON YOU: ${t.title ?: "Event Alert"}")
            .setContentText("${t.category} • Date: ${t.date ?: "Today"}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(t.receivedAt.toInt(), n)
    }
}

// ========================================================
// 4. RECEIVERS & BACKGROUND SERVICE
// ========================================================
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = msgs.groupBy { it.originatingAddress ?: "?" }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                bySender.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    if (PrefsStore.isEngineEnabled(context)) {
                        val ticket = TicketParser.parse(body, sender)
                        if (ticket != null && ticket.category != "UNKNOWN") {
                            AppDb.get(context).tickets().insert(ticket)
                            TicketNotifier.show(context, ticket)
                        }
                    }
                }
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SmsForegroundService.start(context)
        }
    }
}

class SmsForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopPI = PendingIntent.getService(
            this, 0, Intent(this, SmsForegroundService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI ON YOU Active")
            .setContentText("Watching for tickets • 100% On-Device")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(0, "Pause", stopPI)
            .build()

        ServiceCompat.startForeground(this, 1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, SmsForegroundService::class.java))
    }
}

object BatteryOptimization {
    fun requestIgnore(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

// ========================================================
// 5. MAIN ACTIVITY & COMPOSE UI
// ========================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ctx = LocalContext.current
                var done by remember { mutableStateOf(PrefsStore.isSetupDone(ctx)) }
                if (!done) {
                    OnboardingScreen {
                        PrefsStore.setSetupDone(ctx)
                        done = true
                    }
                } else {
                    DashboardScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val smsPerms = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    )
    var step by remember { mutableStateOf(0) }

    LaunchedEffect(smsPerms.allPermissionsGranted, step) {
        if (smsPerms.allPermissionsGranted && step == 1) {
            step = 2
        } else if (step == 2) {
            BatteryOptimization.requestIgnore(ctx)
            SmsForegroundService.start(ctx)
            onDone()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0F19), Color(0xFF111827))))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AI ON YOU", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Thank you! Let's start your day with AI.", style = MaterialTheme.typography.titleMedium, color = Color(0xFF10B981), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Grant permissions once. Runs silently 24/7 in background to manage your event reminders.", color = Color(0xFF9CA3AF), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    step = 1
                    smsPerms.launchMultiplePermissionRequest()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) { Text("⚡ Start AI Engine", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(PrefsStore.isEngineEnabled(ctx)) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F19)).padding(20.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(if (active) Color(0xFF10B981) else Color(0xFF6B7280), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (active) "AI Engine: ACTIVE" else "AI Engine: PAUSED", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("On-Device • Private • 24/7", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                }
                Switch(checked = active, onCheckedChange = {
                    active = it
                    PrefsStore.setEngineEnabled(ctx, it)
                })
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Recent Events & Reminders", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text("Listening for incoming SMS in background...", color = Color(0xFF6B7280), fontSize = 14.sp)
    }
}
