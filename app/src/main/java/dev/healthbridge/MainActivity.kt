package dev.healthbridge

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val permissionRequest =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(layout)
            }
        )
        layout.addView(TextView(this).apply { text = getString(R.string.permission_rationale) })
        status = TextView(this)
        layout.addView(status)
        val settings = Settings(this)
        fun input(hint: String, text: String) =
            EditText(this).apply {
                this.hint = hint
                setText(text)
                layout.addView(this)
            }
        val endpoint =
            input("HTTPS webhook URL", settings.endpoint).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
        val token =
            input("Bearer token and HMAC key", settings.token).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val minutes =
            input("Interval in minutes, minimum 15", settings.minutes.toString()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        fun button(label: String, action: () -> Unit) {
            layout.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                }
            )
        }
        button("Save settings") {
            lifecycleScope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        AppState.mutex.withLock {
                            runCatching {
                                    val url = Transport.validateUrl(endpoint.text.toString())
                                    val secret = token.text.toString()
                                    require(
                                        secret.isNotBlank() &&
                                            secret.none { it.isISOControl() } &&
                                            secret.all { it.code in 32..126 }
                                    ) {
                                        "Token must be nonempty printable ASCII"
                                    }
                                    val interval = minutes.text.toString().toLongOrNull()
                                    require(
                                        interval != null && interval >= 15 && interval <= 525600
                                    ) {
                                        "Interval must be 15 to 525600 minutes"
                                    }
                                    val current = Settings(this@MainActivity)
                                    check(
                                        AppState.store(this@MainActivity).pending() == null ||
                                            (url == current.endpoint && secret == current.token)
                                    ) {
                                        "Pending batch locks endpoint and token until delivery succeeds"
                                    }
                                    current.save(url, secret, interval)
                                    Schedule.pauseHealth(this@MainActivity)
                                    "Settings saved"
                                }
                                .getOrElse { it.message ?: "Could not save settings" }
                        }
                    }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
        button("Grant health permissions") {
            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Read health data")
                    .setMessage(
                        "Health Bridge reads sleep, HRV, resting heart rate, heart rate samples, steps and exercise, then sends them to your saved HTTPS webhook. All six read permissions are needed for sync. Background read access is optional where supported. You can revoke access in Health Connect."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        permissionRequest.launch(HealthReader(this).requestedPermissions())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            else
                Toast.makeText(
                        this,
                        "Install or update Health Connect on supported devices",
                        Toast.LENGTH_LONG,
                    )
                    .show()
        }
        button("Sync now / retry retained batch") {
            Schedule.manual(this)
            refresh()
        }
        button("Test webhook (empty batch)") {
            Schedule.manual(this, test = true)
            refresh()
        }
        lifecycleScope.launch {
            while (true) {
                refresh()
                delay(2000)
            }
        }
        Schedule.pauseHealth(this)
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val text =
                withContext(Dispatchers.IO) {
                    val sdk = HealthConnectClient.getSdkStatus(this@MainActivity)
                    val availability =
                        when (sdk) {
                            HealthConnectClient.SDK_AVAILABLE -> "Available"
                            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                                "Install or update Health Connect"
                            else -> "Unsupported device"
                        }
                    val permissions =
                        if (sdk == HealthConnectClient.SDK_AVAILABLE)
                            runCatching {
                                    HealthConnectClient.getOrCreate(this@MainActivity)
                                        .permissionController
                                        .getGrantedPermissions()
                                }
                                .getOrDefault(emptySet())
                        else emptySet()
                    val missing = HealthReader.permissions - permissions
                    val store = AppState.store(this@MainActivity)
                    val configured = Settings(this@MainActivity).endpoint.isNotBlank()
                    val result = store.status() ?: SyncStatus()
                    val pending = store.pending()
                    "Health Connect: $availability\nPermissions: ${6 - missing.size}/6; ${if (missing.isEmpty()) "granted" else if (missing.size == 6) "denied / not granted, sync skipped" else "partial, sync skipped"}\nMissing: ${missing.joinToString { it.substringAfterLast('.') }}\nConnection: ${if (configured) "configured; see last result for delivery status" else "not configured"}\nBackground read: ${if (androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in permissions) "granted" else "not granted; collection requires open app"}\nLast sync: ${result.lastSuccess}\nResult: ${result.result}\nQueue: ${pending?.let { if (RetryPolicy.exhausted(it.attempts)) "exhausted, retained" else "pending, attempts ${it.attempts}/5" } ?: "empty"}"
                }
            status.text = text
        }
    }
}
