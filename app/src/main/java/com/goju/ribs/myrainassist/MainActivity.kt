package com.goju.ribs.myrainassist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.goju.ribs.myrainassist.notification.LastCycleDebug
import com.goju.ribs.myrainassist.notification.RainEventLog
import com.goju.ribs.myrainassist.service.NotificationEventBus
import com.goju.ribs.myrainassist.service.RainForecastBus
import com.goju.ribs.myrainassist.service.RainMonitorService
import com.goju.ribs.myrainassist.ui.OnboardingStepInfo
import com.goju.ribs.myrainassist.ui.PermissionOnboardingScreen
import com.goju.ribs.myrainassist.ui.RainEventLogScreen
import com.goju.ribs.myrainassist.ui.RainWebViewScreen
import com.goju.ribs.myrainassist.ui.theme.MyRainAssistTheme
import com.goju.ribs.myrainassist.update.AppUpdater
import com.goju.ribs.myrainassist.update.BuildConfigVersion
import com.goju.ribs.myrainassist.update.LatestApp
import com.goju.ribs.myrainassist.update.UpdateDialog
import com.goju.ribs.myrainassist.update.UpdateUi
import com.goju.ribs.myrainassist.webview.NativeUiBridge
import com.goju.ribs.myrainassist.webview.WebBridge
import java.io.File
import kotlinx.coroutines.launch

private const val KEY_FINE_LOCATION_ASKED = "fine_location_asked"
private const val KEY_UPDATE_LATER_CODE = "update_later_code"
private const val KEY_UPDATE_LATER_AT = "update_later_at"
// "나중에" keeps that version quiet for a day; a newer one still prompts right away.
private const val UPDATE_LATER_MS = 24L * 60 * 60 * 1000
// latest.json is checked on each return to the foreground, at most this often.
private const val UPDATE_CHECK_INTERVAL_MS = 60L * 60 * 1000

private enum class OnboardingStep { NOTIFICATIONS, LOCATION, BACKGROUND_LOCATION, BATTERY_OPTIMIZATION }

private val ONBOARDING_STEPS = listOf(
    OnboardingStep.NOTIFICATIONS,
    OnboardingStep.LOCATION,
    OnboardingStep.BACKGROUND_LOCATION,
    OnboardingStep.BATTERY_OPTIMIZATION,
)

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var stepIndex by mutableIntStateOf(0)
    // Starts true so the very first onResume after a cold launch is also treated as "returning to
    // the foreground" (immediate forecast check, position refresh), not just actual background returns.
    private var wasStopped = true

    private lateinit var prefs: SharedPreferences

    private var updateUi by mutableStateOf<UpdateUi>(UpdateUi.None)
    private var showEventLog by mutableStateOf(false)
    // The page draws its own 기록 button when it says so (NativeUiBridge); until then, the app's overlay one.
    private var webOwnsEventLogButton by mutableStateOf(false)
    private val nativeUiBridge = NativeUiBridge(
        onOpenEventLog = { showEventLog = true },
        onWebOwnsEventLogButton = { webOwnsEventLogButton = it },
    )
    private var lastUpdateCheckAt = 0L
    // Downloaded APK waiting on the "install unknown apps" switch the user is sent to flip.
    private var pendingApk: File? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    // Requesting FINE alongside COARSE is what makes the system show the "precise location" toggle
    // at all — asking for COARSE alone (as this used to) silently caps every fix to a ~2km grid.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            prefs.edit().putBoolean(KEY_FINE_LOCATION_ASKED, true).apply()
            advance()
        }
    private val backgroundLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { advance() }
    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingApk
            pendingApk = null
            if (apk != null && AppUpdater.canInstall(this)) AppUpdater.launchInstaller(this, apk)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = getSharedPreferences("onboarding_state", Context.MODE_PRIVATE)
        skipAlreadySatisfiedSteps()
        // Anything still in the update folder belongs to an install that already went in (or was abandoned).
        AppUpdater.clearDownloads(this)

        setContent {
            MyRainAssistTheme {
                UpdateDialog(
                    state = updateUi,
                    currentVersionName = BuildConfigVersion.name(this),
                    onUpdate = ::startUpdate,
                    onLater = ::postponeUpdate,
                )
                val index = stepIndex
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (index >= ONBOARDING_STEPS.size) {
                        var eventLogRefreshKey by remember { mutableIntStateOf(0) }
                        BackHandler(enabled = showEventLog) { showEventLog = false }
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            RainWebViewScreen(
                                onWebViewCreated = { webView = it },
                                nativeUiBridge = nativeUiBridge,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (showEventLog) {
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    RainEventLogScreen(
                                        entries = remember(showEventLog, eventLogRefreshKey) { RainEventLog.readAll(this@MainActivity) },
                                        lastCycle = remember(showEventLog, eventLogRefreshKey) { LastCycleDebug.read(this@MainActivity) },
                                        onBack = { showEventLog = false },
                                        onClear = {
                                            RainEventLog.clear(this@MainActivity)
                                            eventLogRefreshKey++
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else if (!webOwnsEventLogButton) {
                                Surface(
                                    onClick = { showEventLog = true },
                                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 4.dp,
                                ) {
                                    Text(
                                        "기록",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        val step = ONBOARDING_STEPS[index]
                        PermissionOnboardingScreen(
                            step = infoFor(step),
                            onRequestPermission = { requestFor(step) },
                            onSkip = { markSkipped(step); advance() },
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RainForecastBus.forecast.collect { result ->
                    val view = webView
                    if (result != null && view != null) {
                        WebBridge.pushForecastToWebView(view, result)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationEventBus.events.collect { event ->
                    val view = webView
                    if (event != null && view != null) {
                        WebBridge.pushNotificationToWebView(view, event)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        skipAlreadySatisfiedSteps()
        if (stepIndex >= ONBOARDING_STEPS.size) {
            startMonitoringService()
            // Pulling down the notification shade only triggers onPause, not onStop, so skip this
            // then — only re-locate when actually returning from background. No page reload: the
            // WebView interface re-locates and redraws in place (see docs/webview-interface.md).
            if (wasStopped) {
                webView?.let { WebBridge.requestPositionRefresh(it) }
                // Otherwise the displayed state can lag up to a full poll cycle (~5.5 min) behind
                // reality — e.g. rain already started or already stopped by the time the user opens
                // the app, but the alert/ongoing text still reflects the last scheduled check.
                RainMonitorService.requestImmediateCheck(this)
                checkForUpdate()
                wasStopped = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (updateUi != UpdateUi.None || now - lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheckAt = now
        lifecycleScope.launch {
            val latest = AppUpdater.fetchLatest() ?: return@launch
            if (!AppUpdater.isNewer(latest, BuildConfigVersion.code(this@MainActivity))) return@launch
            val postponed = prefs.getInt(KEY_UPDATE_LATER_CODE, -1) == latest.versionCode &&
                System.currentTimeMillis() - prefs.getLong(KEY_UPDATE_LATER_AT, 0) < UPDATE_LATER_MS
            if (!postponed && updateUi == UpdateUi.None) updateUi = UpdateUi.Available(latest)
        }
    }

    private fun postponeUpdate() {
        val latest = when (val ui = updateUi) {
            is UpdateUi.Available -> ui.latest
            is UpdateUi.Failed -> ui.latest
            else -> null
        }
        if (latest != null) {
            prefs.edit()
                .putInt(KEY_UPDATE_LATER_CODE, latest.versionCode)
                .putLong(KEY_UPDATE_LATER_AT, System.currentTimeMillis())
                .apply()
        }
        updateUi = UpdateUi.None
    }

    private fun startUpdate(latest: LatestApp) {
        updateUi = UpdateUi.Downloading(latest, 0f)
        lifecycleScope.launch {
            var shown = 0
            val apk = AppUpdater.download(this@MainActivity, latest) { progress ->
                // Recompose on whole percents only, not every 64KB chunk.
                val percent = (progress * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    runOnUiThread { if (updateUi is UpdateUi.Downloading) updateUi = UpdateUi.Downloading(latest, progress) }
                }
            }
            if (apk == null) {
                updateUi = UpdateUi.Failed(latest)
                return@launch
            }
            updateUi = UpdateUi.None
            if (AppUpdater.canInstall(this@MainActivity)) {
                AppUpdater.launchInstaller(this@MainActivity, apk)
            } else {
                pendingApk = apk
                installPermissionLauncher.launch(AppUpdater.installPermissionSettingsIntent(this@MainActivity))
            }
        }
    }

    private fun skipAlreadySatisfiedSteps() {
        while (stepIndex < ONBOARDING_STEPS.size && isStepDone(ONBOARDING_STEPS[stepIndex])) {
            stepIndex++
        }
    }

    private fun advance() {
        stepIndex++
        skipAlreadySatisfiedSteps()
        if (stepIndex >= ONBOARDING_STEPS.size) {
            startMonitoringService()
        }
    }

    private fun markSkipped(step: OnboardingStep) {
        prefs.edit().putBoolean("skipped_${step.name}", true).apply()
    }

    private fun isStepDone(step: OnboardingStep): Boolean {
        if (prefs.getBoolean("skipped_${step.name}", false)) return true
        return when (step) {
            OnboardingStep.NOTIFICATIONS ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            // Requires the FINE request to have actually been shown, not just COARSE being granted —
            // otherwise installs that onboarded before FINE was added would never see it (COARSE
            // alone would already read as "done").
            OnboardingStep.LOCATION ->
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) && prefs.getBoolean(KEY_FINE_LOCATION_ASKED, false)
            OnboardingStep.BACKGROUND_LOCATION ->
                !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            OnboardingStep.BATTERY_OPTIMIZATION ->
                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestFor(step: OnboardingStep) {
        when (step) {
            OnboardingStep.NOTIFICATIONS -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            OnboardingStep.LOCATION -> locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            OnboardingStep.BACKGROUND_LOCATION ->
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            OnboardingStep.BATTERY_OPTIMIZATION -> {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
                batteryOptimizationLauncher.launch(intent)
            }
        }
    }

    private fun infoFor(step: OnboardingStep): OnboardingStepInfo = when (step) {
        OnboardingStep.NOTIFICATIONS -> OnboardingStepInfo(
            title = "알림 권한",
            rationale = "비가 다가오는 것을 알려드리려면 알림 권한이 필요해요.",
            actionLabel = "알림 허용",
        )
        OnboardingStep.LOCATION -> OnboardingStepInfo(
            title = "위치 권한",
            rationale = "정확한 강수 예보를 위해 위치 권한이 필요해요. 다음 화면에서 '정확한 위치'를 함께 켜주세요.",
            actionLabel = "위치 허용",
        )
        OnboardingStep.BACKGROUND_LOCATION -> OnboardingStepInfo(
            title = "백그라운드 위치 권한",
            rationale = "앱을 꺼도 강수 감시가 계속되려면 백그라운드 위치 권한이 필요해요. " +
                "다음 화면에서 '항상 허용'을 선택해주세요.",
            actionLabel = "설정으로 이동",
        )
        OnboardingStep.BATTERY_OPTIMIZATION -> OnboardingStepInfo(
            title = "배터리 최적화 제외",
            rationale = "백그라운드 강수 감시가 중단되지 않으려면 배터리 최적화 대상에서 제외해야 해요.",
            actionLabel = "설정으로 이동",
        )
    }

    private fun startMonitoringService() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        ContextCompat.startForegroundService(this, Intent(this, RainMonitorService::class.java))
    }
}
