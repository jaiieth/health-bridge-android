package dev.healthbridge

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var progress: TextView
    private lateinit var status: TextView
    private lateinit var stateIcon: ImageView
    private lateinit var lastSync: TextView
    private lateinit var testStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var host: TextView
    private lateinit var savedToken: TextView
    private lateinit var control: Switch
    private lateinit var syncNow: Button
    private lateinit var sendTest: Button
    private lateinit var setupMessage: TextView
    private lateinit var tokenField: EditText
    private lateinit var endpointField: EditText
    private lateinit var editEndpoint: Button
    private var changingControl = false
    private var refreshing = false
    private val enabled get() = Settings(this).syncEnabled
    private val syncControl by lazy {
        SyncControl(Settings(this), object : SyncScheduler {
            override fun schedule(minutes: Long) = Schedule.periodic(this@MainActivity, minutes)
            override fun cancel() = Schedule.pauseHealth(this@MainActivity)
        })
    }
    private val fieldErrors = mutableMapOf<EditText, TextView>()
    private val permissionRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.HealthBridgeTheme)
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = if (resources.configuration.screenWidthDp >= 600) dim(R.dimen.hb_space_xl) * 2 else dim(R.dimen.hb_space_lg)
            setPadding(gutter, gutter, gutter, dim(R.dimen.hb_space_xl))
            setBackgroundColor(color(R.color.hb_background))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(page)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
        text(page, "Health Bridge", R.dimen.hb_type_title, bold = true)
        text(page, "Sync is OFF by default. Save your setup, then use Send test to send an empty batch. Health delivery is paused during containment.")

        val initial = SyncStatusDisplay.Snapshot(enabled, false, false)
        val sync = card(page, "Sync control")
        control = Switch(this).apply {
            isSaveEnabled = false
            text = SyncStatusDisplay.consentLabel(initial)
            isEnabled = SyncStatusDisplay.controls(initial).consentEnabled
            isChecked = initial.enabled
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(color(R.color.hb_on_disabled), color(R.color.hb_text))))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.hb_type_section))
            thumbTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(color(R.color.hb_on_disabled), color(R.color.hb_primary)))
            trackTintList = ColorStateList.valueOf(color(R.color.hb_disabled))
            minHeight = dim(R.dimen.hb_touch_min)
            minimumWidth = dim(R.dimen.hb_touch_min)
            setPadding(0, dim(R.dimen.hb_space_sm), 0, dim(R.dimen.hb_space_sm))
        }
        sync.addView(control, params())
        stateIcon = ImageView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sync.addView(stateIcon, LinearLayout.LayoutParams(dim(R.dimen.hb_space_lg), dim(R.dimen.hb_space_lg)))
        status = text(sync, if (initial.freshChunkingEnabled) "Loading recorded state…"
            else "${SyncStatusDisplay.State.GATED.label}. ${SyncStatusDisplay.buildUnavailableReason}")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        syncNow = button(sync, "Record paused health status") {
            if (enabled && ChunkContracts.freshChunkingEnabled) {
                Schedule.manual(this)
                refresh()
            }
        }
        syncNow.isEnabled = SyncStatusDisplay.controls(initial).manualEnabled
        if (!initial.freshChunkingEnabled) syncNow.text = SyncStatusDisplay.buildUnavailableReason
        text(sync, "Progress", bold = true)
        progress = text(sync, SyncStatusDisplay.progress())
        text(sync, "Last successful health sync", bold = true)
        lastSync = text(sync, "Never recorded")

        val setup = card(page, "Connection & setup")
        val settings = Settings(this)
        host = text(setup, "Destination · ${SyncStatusDisplay.host(settings.endpoint)}", bold = true)
        text(setup, "Only the host is shown here. Edit to view the full endpoint.")
        val endpointGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        setup.addView(endpointGroup, params())
        val endpoint = input(endpointGroup, "HTTPS endpoint", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        endpointField = endpoint
        editEndpoint = button(setup, "Edit endpoint") {
            endpoint.setText(Settings(this).endpoint)
            endpointGroup.visibility = View.VISIBLE
            endpoint.requestFocus()
        }
        savedToken = text(setup, SyncStatusDisplay.tokenIndicator(settings.token.isNotBlank()))
        val token = input(setup, "New bearer token and HMAC key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText(SyncStatusDisplay.tokenFieldValue())
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        tokenField = token
        val minutes = input(setup, "Interval in minutes · 15 to 525600", InputType.TYPE_CLASS_NUMBER)
        minutes.setText(getString(R.string.interval_value, settings.minutes))
        setupMessage = text(setup, "Saving settings updates the interval when sync is ON. It does not turn sync on.")
        setupMessage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        val save = button(setup, "Save settings") {}
        save.setOnClickListener {
            fieldErrors.values.forEach { it.text = ""; it.visibility = View.GONE }
            val rawUrl = if (endpointGroup.visibility == View.VISIBLE) endpoint.text.toString() else Settings(this).endpoint
            val url = runCatching { Transport.validateUrl(rawUrl) }.getOrNull()
            val entered = token.text.toString()
            val validToken = entered.isEmpty() || entered.isNotBlank() && entered.all { it.code in 32..126 }
            val interval = minutes.text.toString().toLongOrNull()
            if (url == null) {
                endpointGroup.visibility = View.VISIBLE
                showError(endpoint, "Enter an HTTPS URL without user info or fragment.")
            }
            if (!validToken || (entered.isEmpty() && Settings(this).token.isBlank()))
                showError(token, "Enter a nonempty printable ASCII token.")
            if (interval == null || interval !in 15..525600)
                showError(minutes, "Use 15 to 525600 minutes.")
            val firstError = listOf(endpoint, token, minutes).firstOrNull { fieldErrors.getValue(it).visibility == View.VISIBLE }
            if (firstError != null) { firstError.requestFocus(); return@setOnClickListener }
            save.isEnabled = false
            save.setText(R.string.saving_settings)
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) {
                    AppState.mutex.withLock {
                        val current = Settings(this@MainActivity)
                        val secret = entered.ifEmpty { current.token }
                        if (AppState.store(this@MainActivity).pending() != null &&
                            (url != current.endpoint || secret != current.token)) {
                            "A retained batch locks the endpoint and token. Endpoint and token cannot change during containment."
                        } else {
                            runCatching {
                                syncControl.save(requireNotNull(url), secret, requireNotNull(interval))
                                "Settings saved. ${SyncStatusDisplay.consentLabel(SyncStatusDisplay.Snapshot(enabled, false, false))}."
                            }.getOrDefault("Could not save settings. Try again.")
                        }
                    }
                }
                setupMessage.text = message
                token.text.clear()
                if (message.startsWith("Settings saved")) endpointGroup.visibility = View.GONE
                save.isEnabled = true
                save.setText(R.string.save_settings)
                refresh()
            }
        }

        val access = card(page, "Health access")
        text(access, getString(R.string.permission_rationale))
        permissionStatus = text(access, "Checking permissions…")
        button(access, "Grant health permissions") {
            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Read health data")
                    .setMessage("Health Bridge reads sleep, HRV, resting heart rate, heart rate samples, steps and exercise. Health delivery is currently paused. Empty webhook tests send no health records. All six read permissions are needed for future collection. Background read access is optional. You can revoke access in Health Connect.")
                    .setPositiveButton("Continue") { _, _ -> permissionRequest.launch(HealthReader(this).requestedPermissions()) }
                    .setNegativeButton("Cancel", null).show()
            else {
                permissionStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                permissionStatus.setText(R.string.health_connect_unavailable)
            }
        }

        val test = card(page, "Test destination")
        text(test, "Sends an empty batch. This does not enable health sync or record a successful health sync.")
        sendTest = button(test, "Send test") {
            Schedule.manual(this, test = true)
            testStatus.setText(R.string.test_requested)
            sendTest.isEnabled = false
        }
        testStatus = text(test, "No recorded test acknowledgement.")
        testStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        control.setOnCheckedChangeListener { _, checked ->
            if (!changingControl) {
                control.isEnabled = false
                control.setText(R.string.saving_sync_preference)
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        AppState.mutex.withLock {
                            syncControl.setEnabled(checked)
                        }
                    }
                    if (!ok) setupMessage.setText(R.string.control_save_failed)
                    control.isEnabled = enabled || ChunkContracts.freshChunkingEnabled
                    refresh()
                }
            }
        }
        lifecycleScope.launch {
            while (true) { refresh(); delay(2000) }
        }
    }

    override fun onStart() { super.onStart(); AppState.foreground = true }
    override fun onStop() { AppState.foreground = false; super.onStop() }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val available = HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE
                    val permissions = if (available) runCatching {
                        HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                    }.getOrDefault(emptySet()) else emptySet()
                    val settings = Settings(this@MainActivity)
                    val store = AppState.store(this@MainActivity)
                    val result = store.status() ?: SyncStatus()
                    val pending = store.pending()
                    Triple(SyncStatusDisplay.Snapshot(settings.syncEnabled, settings.endpoint.isNotBlank() && settings.token.isNotBlank(),
                        permissions.containsAll(HealthReader.permissions), result.result, result.lastSuccess,
                        pending != null),
                        "${if (available) "Health Connect available" else "Health Connect unavailable"}\n${HealthReader.permissions.count { it in permissions }} of 6 read permissions granted.\nBackground read ${if (androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in permissions) "granted" else "not granted. Collection requires the app to be open"}.",
                        SyncStatusDisplay.host(settings.endpoint) to settings.token.isNotBlank())
                }
                val s = snapshot.first
                val display = SyncStatusDisplay.format(s)
                val controls = SyncStatusDisplay.controls(s)
                val stateColor = color(when (display.state) {
                    SyncStatusDisplay.State.OFF -> R.color.hb_off
                    SyncStatusDisplay.State.BLOCKED -> R.color.hb_blocked
                    SyncStatusDisplay.State.DONE, SyncStatusDisplay.State.PARTIAL -> R.color.hb_waiting
                    SyncStatusDisplay.State.IO_FAILURE -> R.color.hb_blocked
                    SyncStatusDisplay.State.PAUSED, SyncStatusDisplay.State.GATED -> R.color.hb_paused
                    SyncStatusDisplay.State.WAITING -> R.color.hb_waiting
                    SyncStatusDisplay.State.UNKNOWN -> R.color.hb_unknown
                })
                status.text = getString(R.string.status_details, display.state.label, display.reason, display.action)
                status.setTextColor(stateColor)
                stateIcon.setImageResource(when (display.state) {
                    SyncStatusDisplay.State.OFF -> R.drawable.ic_stop
                    SyncStatusDisplay.State.BLOCKED -> R.drawable.ic_warning
                    SyncStatusDisplay.State.DONE, SyncStatusDisplay.State.PARTIAL -> R.drawable.ic_clock
                    SyncStatusDisplay.State.IO_FAILURE -> R.drawable.ic_warning
                    SyncStatusDisplay.State.PAUSED, SyncStatusDisplay.State.GATED -> R.drawable.ic_pause
                    SyncStatusDisplay.State.WAITING -> R.drawable.ic_clock
                    SyncStatusDisplay.State.UNKNOWN -> R.drawable.ic_help
                })
                stateIcon.imageTintList = ColorStateList.valueOf(stateColor)
                changingControl = true
                control.isChecked = s.enabled
                control.text = SyncStatusDisplay.consentLabel(s)
                control.isEnabled = controls.consentEnabled
                changingControl = false
                syncNow.isEnabled = controls.manualEnabled
                sendTest.isEnabled = controls.testEnabled
                testStatus.accessibilityLiveRegion = if (s.result.startsWith("BLOCKED:") || s.result.contains(": RETRY") || s.result.contains(": BLOCKED")) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE
                progress.text = SyncStatusDisplay.progress(s.result)
                testStatus.text = SyncStatusDisplay.testResult(s.result)
                syncNow.text = if (!s.freshChunkingEnabled) SyncStatusDisplay.buildUnavailableReason
                else if (syncNow.isEnabled) "Record paused health status" else "Health work unavailable: check switch, setup and access"
                sendTest.text = if (sendTest.isEnabled) "Send test" else "Send test unavailable: save endpoint and token"
                lastSync.text = SyncStatusDisplay.lastSuccess(s.lastSuccess, Instant.now(), ZoneId.systemDefault())
                permissionStatus.text = snapshot.second
                host.text = getString(R.string.destination_host, snapshot.third.first)
                savedToken.text = if (s.pending) "Token editing is disabled because a retained batch locks the endpoint and token." else SyncStatusDisplay.tokenIndicator(snapshot.third.second)
                tokenField.isEnabled = !s.pending
                endpointField.isEnabled = !s.pending
                editEndpoint.isEnabled = !s.pending
                editEndpoint.text = if (s.pending) "Endpoint locked by retained batch" else "Edit endpoint"
            } finally { refreshing = false }
        }
    }

    private fun dim(id: Int) = resources.getDimensionPixelSize(id)
    private fun color(id: Int) = getColor(id)
    private fun params() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dim(R.dimen.hb_space_sm)
    }
    private fun shape(fill: Int, corner: Int = R.dimen.hb_corner) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = resources.getDimension(corner)
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), color(R.color.hb_outline))
    }
    private fun card(parent: LinearLayout, title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dim(R.dimen.hb_space_md)
        setPadding(padding, padding, padding, padding)
        background = shape(R.color.hb_surface)
        elevation = resources.getDimension(R.dimen.hb_elevation)
        parent.addView(this, params().apply { topMargin = dim(R.dimen.hb_space_lg) })
        text(this, title, R.dimen.hb_type_section, bold = true)
    }
    private fun text(parent: LinearLayout, value: String, size: Int = R.dimen.hb_type_body, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(color(if (bold) R.color.hb_text else R.color.hb_muted))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(resources.getDimension(R.dimen.hb_space_xs), 1f)
        parent.addView(this, params())
    }
    private fun input(parent: LinearLayout, label: String, type: Int): EditText {
        val caption = text(parent, label, R.dimen.hb_type_label, bold = true)
        val field = EditText(this).apply {
            id = View.generateViewId(); caption.labelFor = id
            inputType = type
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.hb_type_body))
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(color(R.color.hb_on_disabled), color(R.color.hb_text))))
            setHintTextColor(color(R.color.hb_muted))
            minHeight = dim(R.dimen.hb_touch_min); minimumWidth = dim(R.dimen.hb_touch_min)
            setPadding(dim(R.dimen.hb_space_sm), dim(R.dimen.hb_space_sm), dim(R.dimen.hb_space_sm), dim(R.dimen.hb_space_sm))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(R.color.hb_surface, R.dimen.hb_control_corner).apply {
                    setStroke(dim(R.dimen.hb_space_xs), color(R.color.hb_primary))
                })
                addState(intArrayOf(-android.R.attr.state_enabled), shape(R.color.hb_disabled, R.dimen.hb_control_corner))
                addState(intArrayOf(), shape(R.color.hb_surface, R.dimen.hb_control_corner))
            }
            parent.addView(this, params())
        }
        fieldErrors[field] = text(parent, "").apply {
            labelFor = field.id
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            setTextColor(color(R.color.hb_blocked))
            visibility = View.GONE
        }
        return field
    }
    private fun showError(field: EditText, message: String) {
        fieldErrors.getValue(field).apply { text = message; visibility = View.VISIBLE }
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.hb_type_body))
        minHeight = dim(R.dimen.hb_touch_min); minimumWidth = dim(R.dimen.hb_touch_min)
        setPadding(dim(R.dimen.hb_space_md), dim(R.dimen.hb_space_sm), dim(R.dimen.hb_space_md), dim(R.dimen.hb_space_sm))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(color(R.color.hb_on_disabled), color(R.color.hb_on_primary))))
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(color(R.color.hb_disabled), color(R.color.hb_primary)))
        setOnClickListener { action() }
        parent.addView(this, params())
    }
}
