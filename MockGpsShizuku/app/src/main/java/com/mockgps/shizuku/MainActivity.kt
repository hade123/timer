package com.mockgps.shizuku

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

/**
 * 虚拟定位主界面（方案 B：Shizuku 自动授权 + LocationManager.addTestProvider 注入）
 *
 * 工作流程（按钮顺序就是用户操作顺序）：
 *  ① 检测 Shizuku 状态 → 安装/启动/授权
 *  ② 通过 Shizuku 给本 APP 授予 mock_location AppOps 权限
 *  ③ 输入坐标 → 启动虚拟定位（前台服务保活）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvMockOpsStatus: TextView
    private lateinit var tvRunStatus: TextView
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAccuracy: EditText
    private lateinit var btnShizukuPermit: Button
    private lateinit var btnGrantMock: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnInstallShizuku: Button
    private lateinit var btnOpenDevSettings: Button
    private lateinit var btnEnableLocation: Button
    private lateinit var btnDiagnose: Button
    private lateinit var btnFenceToggle: Button
    private lateinit var tvFenceStatus: TextView
    private lateinit var spinnerPreset: Spinner

    /** VpnService.prepare() 申请回调 */
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LocationFenceVpnService.start(this)
            toast("GPS fence VPN started")
        } else {
            toast("VPN permission denied, cannot start GPS fence")
        }
        refreshAllStatus()
    }

    private var remoteService: IMockGpsService? = null

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = service.toMockGpsService()
            runOnUiThread {
                refreshAllStatus()
                toast("Shizuku service connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            runOnUiThread { refreshAllStatus() }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { reqCode, result ->
        if (reqCode == ShizukuHelper.REQUEST_PERMISSION_CODE) {
            runOnUiThread {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    toast("Shizuku permission granted")
                    bindRemoteIfPossible()
                } else {
                    toast("Shizuku permission denied")
                }
                refreshAllStatus()
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshAllStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            remoteService = null
            refreshAllStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("mockgps", Context.MODE_PRIVATE)

        bindViews()
        bindListeners()
        loadSavedCoords()
        ensureLocationPermission()

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (ShizukuHelper.checkStatus(this) == ShizukuHelper.Status.READY) {
            bindRemoteIfPossible()
        }
        refreshAllStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatus()
        startStatusTicker()
    }

    override fun onPause() {
        super.onPause()
        stopStatusTicker()
    }

    private val statusTickerRunnable = object : Runnable {
        override fun run() {
            refreshAllStatus()
            window.decorView.postDelayed(this, 2000)
        }
    }
    private fun startStatusTicker() {
        window.decorView.removeCallbacks(statusTickerRunnable)
        window.decorView.postDelayed(statusTickerRunnable, 2000)
    }
    private fun stopStatusTicker() {
        window.decorView.removeCallbacks(statusTickerRunnable)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    // -------- View 绑定 --------

    private fun bindViews() {
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvMockOpsStatus = findViewById(R.id.tvMockOpsStatus)
        tvRunStatus = findViewById(R.id.tvRunStatus)
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAccuracy = findViewById(R.id.etAccuracy)
        btnShizukuPermit = findViewById(R.id.btnShizukuPermit)
        btnGrantMock = findViewById(R.id.btnGrantMock)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnInstallShizuku = findViewById(R.id.btnInstallShizuku)
        btnOpenDevSettings = findViewById(R.id.btnOpenDevSettings)
        btnEnableLocation = findViewById(R.id.btnEnableLocation)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnFenceToggle = findViewById(R.id.btnFenceToggle)
        tvFenceStatus = findViewById(R.id.tvFenceStatus)
        spinnerPreset = findViewById(R.id.spinnerPreset)

        val presets = Preset.ALL.map { it.label }
        spinnerPreset.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
    }

    private fun bindListeners() {
        btnShizukuPermit.setOnClickListener {
            when (ShizukuHelper.checkStatus(this)) {
                ShizukuHelper.Status.NOT_INSTALLED -> {
                    toast("Please install Shizuku first")
                    openUrl(ShizukuHelper.DOWNLOAD_URL)
                }
                ShizukuHelper.Status.NOT_RUNNING -> {
                    toast("Start the service in the Shizuku app first (ADB / wireless ADB / root)")
                    openShizukuApp()
                }
                ShizukuHelper.Status.NEED_PERMISSION -> ShizukuHelper.requestPermission()
                ShizukuHelper.Status.READY -> bindRemoteIfPossible()
            }
        }
        btnInstallShizuku.setOnClickListener { openUrl(ShizukuHelper.DOWNLOAD_URL) }
        btnOpenDevSettings.setOnClickListener { openDeveloperOptions() }

        btnGrantMock.setOnClickListener { grantMockOpsViaShizuku() }

        btnEnableLocation.setOnClickListener { enableLocationViaShizuku() }
        btnDiagnose.setOnClickListener { showDiagnostics() }
        btnFenceToggle.setOnClickListener { toggleFence() }

        btnStart.setOnClickListener { startMocking() }
        btnStop.setOnClickListener { stopMocking() }

        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val preset = Preset.ALL.getOrNull(pos) ?: return
                if (preset.lat == 0.0 && preset.lng == 0.0) return
                etLat.setText(preset.lat.toString())
                etLng.setText(preset.lng.toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 实时持久化坐标
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { saveCoords() }
        }
        etLat.addTextChangedListener(watcher)
        etLng.addTextChangedListener(watcher)
        etAccuracy.addTextChangedListener(watcher)
    }

    // -------- 业务 --------

    private fun bindRemoteIfPossible() {
        if (remoteService != null) return
        if (ShizukuHelper.checkStatus(this) != ShizukuHelper.Status.READY) return
        try {
            ShizukuHelper.bindUserService(this, shizukuConnection)
        } catch (e: Throwable) {
            toast("Failed to bind Shizuku service: ${e.message}")
        }
    }

    private fun grantMockOpsViaShizuku() {
        val rs = remoteService
        if (rs == null) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku not connected")
                .setMessage("First tap \"① Request Shizuku permission\" and wait for the green \"ready\" status, then try again.\n\n" +
                        "Without Shizuku, tap \"Developer options\" and set it manually:\n" +
                        "  → Select mock location app → \"Fake GPS (Shizuku)\"")
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
            return
        }
        Thread {
            val code = try { rs.grantMockLocation(packageName) } catch (e: Throwable) { -1 }
            val q = try { rs.queryMockLocation(packageName) } catch (e: Throwable) { e.message ?: "" }
            runOnUiThread {
                if (code == 0) toast("mock_location permission granted") else toast("Grant failed (code=$code)")
                tvMockOpsStatus.text = "mock_location status:\n$q"
                refreshAllStatus()
            }
        }.start()
    }

    private fun enableLocationViaShizuku() {
        val rs = remoteService
        if (rs == null) {
            toast("Connect Shizuku first")
            return
        }
        Thread {
            val code = try { rs.enableLocationServices() } catch (e: Throwable) { -1 }
            val mode = try { rs.queryLocationMode() } catch (e: Throwable) { e.message ?: "" }
            runOnUiThread {
                if (code == 0) {
                    toast("System location turned on (mode=$mode)")
                    // 系统定位主开关 OFF→ON 时，部分 ROM 会清空 test provider，
                    // 这里如果服务已在跑，强制重启注入循环以重新注册。
                    if (MockGpsForegroundService.isRunning()) {
                        val (la, ln) = MockGpsForegroundService.providerInstance(this).currentLatLng()
                        val acc = etAccuracy.text.toString().toFloatOrNull() ?: 1f
                        MockGpsForegroundService.stopService(this)
                        // 给系统 200ms 处理 stopService
                        window.decorView.postDelayed({
                            MockGpsForegroundService.startService(this, la, ln, acc)
                            toast("Fake provider re-registered")
                            refreshAllStatus()
                        }, 200)
                    }
                } else {
                    toast("Failed (code=$code). Turn on Location manually in Settings")
                    openSystemLocationSettings()
                }
                refreshAllStatus()
            }
        }.start()
    }

    private fun showDiagnostics() {
        val rs = remoteService
        val running = MockGpsForegroundService.isRunning()
        val (la, ln) = MockGpsForegroundService.providerInstance(this).currentLatLng()

        val header = StringBuilder()
            .append("== App status ==\n")
            .append("• Injection loop: ${if (running) "● Running" else "○ Stopped"}\n")
            .append("• Current coordinates: ${"%.6f".format(la)}, ${"%.6f".format(ln)}\n")
            .append("• Update rate: 2 Hz (every 500 ms)\n\n")

        if (rs == null) {
            showLongDialog("Diagnostics", header.toString() + "Shizuku not connected, cannot run dumpsys.")
            return
        }
        Thread {
            val mockOps = try { rs.queryMockLocation(packageName) } catch (e: Throwable) { e.message ?: "" }
            val locMode = try { rs.queryLocationMode() } catch (e: Throwable) { e.message ?: "" }
            val dump = try { rs.dumpLocation() } catch (e: Throwable) { e.message ?: "" }
            val sysCheck = StringBuilder()
                .append("== System status (via Shizuku) ==\n")
                .append("• mock_location AppOps:\n  $mockOps\n")
                .append("• System location_mode = $locMode  (3 = high accuracy, 0 = off)\n\n")
                .append("== dumpsys location (key parts) ==\n")
                .append(dump.ifBlank { "(empty)" })
                .append("\n\n== If map apps still show your real location ==\n")
                .append("1. location_mode must be 3 (not 0/1/2)\n")
                .append("2. mock_location AppOps must be allow\n")
                .append("3. The gps provider line in dumpsys should show 'mock=true' or 'TestProvider'\n")
                .append("4. Turn off Wi-Fi + mobile data and test the map app again\n")
                .append("5. In the map app, set location mode to \"GPS only\" if available")
            runOnUiThread {
                showLongDialog("Diagnostics", header.toString() + sysCheck.toString())
            }
        }.start()
    }

    private fun showLongDialog(title: String, body: String) {
        val sv = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = body
            textSize = 11f
            setPadding(40, 24, 40, 24)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toggleFence() {
        if (LocationFenceVpnService.isRunning) {
            LocationFenceVpnService.stop(this)
            toast("GPS fence stopped")
            window.decorView.postDelayed({ refreshAllStatus() }, 300)
            return
        }
        // 启动前需要 VpnService.prepare（首次会弹 Android 系统授权对话框）
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            // 已授权过
            LocationFenceVpnService.start(this)
            toast("GPS fence VPN started")
            window.decorView.postDelayed({ refreshAllStatus() }, 300)
        }
    }

    private fun openSystemLocationSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            toast("Turn on Location manually in Settings")
        }
    }

    private fun startMocking() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val acc = etAccuracy.text.toString().toFloatOrNull() ?: 1f
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            toast("Invalid latitude/longitude")
            return
        }
        ensureLocationPermission()

        // 启动前检查系统定位主开关。OFF 时再 addTestProvider 也是无效的，
        // 高德/百度只有在主开关开启后才会读取我们的 mock 坐标。
        if (!isSystemLocationOn()) {
            AlertDialog.Builder(this)
                .setTitle("System Location is off")
                .setMessage("Fake location needs the system Location switch to be ON.\n\n" +
                        "Recommended: let this app turn it on via Shizuku, so the phone does not reset the fake provider.")
                .setPositiveButton("Turn on via Shizuku") { _, _ ->
                    enableLocationViaShizuku()
                    // enableLocationViaShizuku 成功后会自动重启服务（如果之前启动过）
                    // 这里再主动启动一次，覆盖「之前未启动」的场景
                    window.decorView.postDelayed({ doStartMockingNow(lat, lng, acc) }, 600)
                }
                .setNeutralButton("Open Settings") { _, _ ->
                    openSystemLocationSettings()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        doStartMockingNow(lat, lng, acc)
    }

    private fun doStartMockingNow(lat: Double, lng: Double, acc: Float) {
        try {
            MockGpsForegroundService.startService(this, lat, lng, acc)
            toast("Fake location started")
        } catch (e: SecurityException) {
            AlertDialog.Builder(this)
                .setTitle("Missing mock_location permission")
                .setMessage("The system refused fake location. Make sure one of these is true:\n\n" +
                        "(Recommended) mock_location granted via Shizuku\n" +
                        "(Manual) Developer options → Select mock location app → this app")
                .setPositiveButton("Open Developer options") { _, _ -> openDeveloperOptions() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refreshAllStatus()
    }

    private fun stopMocking() {
        MockGpsForegroundService.stopService(this)
        toast("Stopped")
        refreshAllStatus()
    }

    // -------- UI 状态刷新 --------

    private fun refreshAllStatus() {
        val st = ShizukuHelper.checkStatus(this)
        val sticker = when (st) {
            ShizukuHelper.Status.READY -> "✓"
            else -> "✗"
        }
        tvShizukuStatus.text = "[$sticker Shizuku]\n${ShizukuHelper.statusDescription(st)}"
        btnShizukuPermit.isEnabled = (st != ShizukuHelper.Status.READY)
        btnInstallShizuku.visibility =
            if (st == ShizukuHelper.Status.NOT_INSTALLED) android.view.View.VISIBLE else android.view.View.GONE

        // mock ops 状态由远程服务异步查询；这里只更新「未连接」时的占位
        if (remoteService == null) {
            tvMockOpsStatus.text = "mock_location status:\n(Shizuku service not connected)"
            btnGrantMock.isEnabled = false
        } else {
            btnGrantMock.isEnabled = true
            // 异步刷新一次
            Thread {
                val q = try { remoteService?.queryMockLocation(packageName) ?: "" }
                catch (e: Throwable) { e.message ?: "" }
                runOnUiThread { tvMockOpsStatus.text = "mock_location status:\n$q" }
            }.start()
        }

        val running = MockGpsForegroundService.isRunning()
        tvRunStatus.text = if (running) {
            val (lat, lng) = MockGpsForegroundService.providerInstance(this).currentLatLng()
            "● Running  at ${"%.6f".format(lat)}, ${"%.6f".format(lng)}"
        } else {
            "○ Stopped"
        }
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        // GPS 围栏 (VpnService) 状态
        val fence = LocationFenceVpnService.isRunning
        tvFenceStatus.text = if (fence) {
            "● GPS fence running  ${LocationFenceVpnService.stats()}"
        } else {
            "○ GPS fence off — Amap/Baidu may still use their own network location"
        }
        btnFenceToggle.text = if (fence) "Stop GPS fence" else "Start GPS fence (block Amap/Baidu network location)"
    }

    // -------- 持久化 --------

    private fun loadSavedCoords() {
        etLat.setText(prefs.getString("lat", "39.908823"))
        etLng.setText(prefs.getString("lng", "116.397470"))
        etAccuracy.setText(prefs.getString("accuracy", "1.0"))
    }

    private fun saveCoords() {
        prefs.edit()
            .putString("lat", etLat.text.toString())
            .putString("lng", etLng.text.toString())
            .putString("accuracy", etAccuracy.text.toString())
            .apply()
    }

    // -------- 工具 --------

    private fun isSystemLocationOn(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                val mode = android.provider.Settings.Secure.getInt(
                    contentResolver, android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF
                )
                mode != android.provider.Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun ensureLocationPermission() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 9001)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("No browser found. Please open $url manually")
        }
    }

    private fun openShizukuApp() {
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) startActivity(launch) else toast("Shizuku is not installed")
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            toast("Cannot open Developer options. Go to Settings → System → Developer options manually")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

private data class Preset(val label: String, val lat: Double, val lng: Double) {
    companion object {
        val ALL = listOf(
            Preset("(Custom)", 0.0, 0.0),
            Preset("Beijing · Tiananmen", 39.908823, 116.397470),
            Preset("Shanghai · People's Square", 31.231706, 121.472644),
            Preset("Guangzhou · Zhujiang New Town", 23.119741, 113.323289),
            Preset("Shenzhen · Ping An Finance Center", 22.536891, 114.057868),
            Preset("Hangzhou · West Lake", 30.246802, 120.149408),
            Preset("Chengdu · Chunxi Road", 30.658232, 104.081534),
            Preset("Wuhan · Yellow Crane Tower", 30.546306, 114.301946),
            Preset("Xi'an · Bell Tower", 34.260930, 108.943109),
            Preset("Hong Kong · Victoria Harbour", 22.293108, 114.169276),
            Preset("Taipei · 101", 25.033964, 121.564468),
            Preset("Tokyo · Shibuya", 35.658034, 139.701636),
            Preset("New York · Times Square", 40.758896, -73.985130),
        )
    }
}
