package com.mdmesh.agent

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * MDMesh agent home / status screen. Doubles as the kiosk HOME surface
 * (`android:lockTaskMode="if_whitelisted"` + HOME intent filter), so when the agent's
 * package is lock-task-allowlisted the system auto-enters lock task here.
 *
 * Shows the real managed state — Device-Owner status, server-issued device id, kiosk state,
 * and the server URL — so a person looking at the device can see it's managed by MDMesh.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceIdStore: DeviceIdStore
    @Inject lateinit var dpmHandle: DpmHandle
    @Inject lateinit var serverConfig: ServerConfigStore
    @Inject lateinit var syncStatus: SyncStatus

    private lateinit var deviceIdValue: TextView
    private lateinit var kioskValue: TextView
    private lateinit var serverValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContentView(buildUi())
        grantSelfNotifications()
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        refresh()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("com.mdmesh.CLEAR_DEVICE_OWNER", false)) {
            runCatching {
                dpmHandle.dpm.clearDeviceOwnerApp(packageName)
            }
            refresh()
            return
        }
        val url = intent.getStringExtra("com.mdmesh.SERVER_URL") ?: intent.getStringExtra("SERVER_URL")
        val token = intent.getStringExtra("com.mdmesh.ENROLL_TOKEN") ?: intent.getStringExtra("ENROLL_TOKEN")
        if (!url.isNullOrBlank()) {
            serverConfig.save(url)
        }
        if (!token.isNullOrBlank()) {
            lifecycleScope.launch {
                com.mdmesh.core.store.EnrollTokenStore(applicationContext).save(token)
                com.mdmesh.core.sync.CheckInWorker.schedule(applicationContext)
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, CheckInService::class.java),
                )
                refresh()
            }
        }
    }

    /** Self-grant POST_NOTIFICATIONS (Device Owner, API 33+) so our FGS notification shows. */
    private fun grantSelfNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val dpm = dpmHandle.dpm
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setPermissionGrantState(
                    dpmHandle.admin,
                    packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        kioskValue.text = if (isLocked()) "Locked (kiosk active)" else "Not locked"
        if (::serverValue.isInitialized) {
            serverValue.text = serverConfig.baseUrl()
        }
        lifecycleScope.launch {
            val id = deviceIdStore.current()
            deviceIdValue.text = if (id.isNullOrBlank()) enrollingLabel() else id
        }
    }

    /** "Enrolling…" plus the last sync error (if any), so a stuck enrollment is diagnosable on-device. */
    private fun enrollingLabel(): String {
        val failure = syncStatus.lastError.value ?: return "Enrolling…"
        val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(failure.atMillis))
        return "Enrolling… (last error: ${failure.message} at $at)"
    }

    private fun isLocked(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    private fun isDeviceOwner(): Boolean =
        dpmHandle.dpm.isDeviceOwnerApp(packageName)

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            setPadding(dp(28), dp(40), dp(28), dp(40))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        root.addView(text("MDMesh", 30f, SIGNAL, bold = true))
        root.addView(text("Device agent", 14f, MUTED).apply { setPadding(0, dp(2), 0, dp(20)) })

        root.addView(label("MANAGEMENT"))
        root.addView(
            text(
                if (isDeviceOwner()) "Device Owner — active" else "Not managed",
                16f,
                if (isDeviceOwner()) OK else ALERT,
            ),
        )
        root.addView(spacer())

        root.addView(label("DEVICE ID"))
        deviceIdValue = text("…", 14f, TEXT, mono = true)
        root.addView(deviceIdValue)
        root.addView(spacer())

        root.addView(label("KIOSK"))
        kioskValue = text("…", 16f, TEXT)
        root.addView(kioskValue)
        root.addView(spacer())

        root.addView(label("AGENT VERSION"))
        root.addView(
            text(
                "${com.mdmesh.agent.BuildConfig.VERSION_NAME} (build ${com.mdmesh.agent.BuildConfig.VERSION_CODE})",
                15f,
                TEXT,
                mono = true,
            ),
        )
        root.addView(spacer())

        root.addView(label("SERVER"))
        serverValue = text(serverConfig.baseUrl(), 13f, MUTED, mono = true)
        root.addView(serverValue)
        root.addView(spacer())

        root.addView(
            text("Managed by MDMesh", 12f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            },
        )

        return ScrollView(this).apply {
            setBackgroundColor(INK)
            addView(root)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
    }

    private fun label(s: String): TextView =
        text(s, 11f, FAINT).apply {
            letterSpacing = 0.10f
            setPadding(0, 0, 0, dp(4))
        }

    private fun spacer(): TextView = TextView(this).apply {
        height = dp(16)
    }

    private fun text(
        s: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val FAINT = Color.parseColor("#5C6675")
        val SIGNAL = Color.parseColor("#F4B942")
        val OK = Color.parseColor("#3FD08A")
        val ALERT = Color.parseColor("#F2545B")
    }
}
