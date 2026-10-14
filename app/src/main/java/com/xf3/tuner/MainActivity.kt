package com.xf3.tuner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var logView: TextView
    private lateinit var deviceStatus: TextView
    private val switches = mutableMapOf<String, Switch>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionRequestCode = 1001

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == permissionRequestCode) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                log("Shizuku 权限已授予")
                refreshAllStates()
            } else {
                log("Shizuku 权限被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.tweakContainer)
        logView = findViewById(R.id.logView)
        deviceStatus = findViewById(R.id.deviceStatus)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        buildSwitches()

        findViewById<Button>(R.id.btnOptimizeAll).setOnClickListener { applyAll(true) }
        findViewById<Button>(R.id.btnRevertAll).setOnClickListener { applyAll(false) }

        checkEnvironment()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun checkEnvironment() {
        val model = android.os.Build.MODEL
        deviceStatus.text = "设备: $model | 目标机型: V2303A"

        if (!ShellRunner.isTargetDevice()) {
            log("警告: 当前设备不是 vivo X Fold3 (V2303A)，部分键可能无效")
        }

        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行，请先启动 Shizuku")
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("请求 Shizuku 权限...")
            Shizuku.requestPermission(permissionRequestCode)
        } else {
            log("Shizuku 就绪")
            refreshAllStates()
        }
    }

    private fun buildSwitches() {
        for (tweak in Tweaks.ALL) {
            val sw = Switch(this)
            sw.text = tweak.title + "\n" + tweak.desc
            sw.textSize = 14f
            sw.setPadding(0, 24, 0, 24)
            sw.isChecked = tweak.defaultOn
            sw.setOnCheckedChangeListener { _, isChecked ->
                applyTweak(tweak, isChecked)
            }
            container.addView(sw)
            switches[tweak.id] = sw
        }
    }

    private fun applyAll(on: Boolean) {
        log("=== ${if (on) "一键优化" else "全部还原"} ===")
        for (tweak in Tweaks.ALL) {
            switches[tweak.id]?.isChecked = on
        }
    }

    private fun applyTweak(tweak: Tweak, on: Boolean) {
        Thread {
            var success = 0
            for (w in tweak.writes) {
                val value = if (on) w.onValue else w.offValue
                if (value.isEmpty()) continue
                val res = ShellRunner.put(w.namespace, w.key, value)
                if (res.ok) success++
            }
            mainHandler.post {
                log("${tweak.title}: ${if (on) "开启" else "关闭"} ($success/${tweak.writes.size})")
            }
        }.start()
    }

    private fun refreshAllStates() {
        Thread {
            for (tweak in Tweaks.ALL) {
                val first = tweak.writes.firstOrNull() ?: continue
                val current = ShellRunner.get(first.namespace, first.key)
                val isOn = current == first.onValue
                mainHandler.post {
                    switches[tweak.id]?.isChecked = isOn
                }
            }
            mainHandler.post { log("状态已刷新") }
        }.start()
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
        }
    }
}
