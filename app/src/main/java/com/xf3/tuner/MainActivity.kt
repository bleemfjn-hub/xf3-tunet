package com.xf3.tuner

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var logView: TextView
    private lateinit var deviceStatus: TextView
    private val switches = mutableMapOf<String, Switch>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionRequestCode = 1001

    /** 批量操作期间抑制开关回调，避免递归触发。 */
    @Volatile
    private var suppressCallbacks = false

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
            val riskTag = when (tweak.risk) {
                Risk.SAFE -> ""
                Risk.CAUTION -> " [发热↑]"
                Risk.RISKY -> " [高风险]"
            }
            sw.text = tweak.title + riskTag + "\n" + tweak.desc
            sw.textSize = 14f
            sw.setTextColor(android.graphics.Color.parseColor("#E6EDF3"))
            sw.setPadding(0, 24, 0, 24)
            sw.isChecked = tweak.defaultOn
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (suppressCallbacks) return@setOnCheckedChangeListener
                applyTweak(tweak, isChecked)
            }
            container.addView(sw)
            switches[tweak.id] = sw
        }
    }

    /**
     * 一键优化 / 全部还原。
     * 用 putBatch 把全部键合并为【一条】sh -c 命令，只创建一次 Shizuku 进程。
     */
    private fun applyAll(on: Boolean) {
        log("=== ${if (on) "一键优化" else "全部还原"} ===")

        if (on) backupOriginalValues()

        suppressCallbacks = true
        for (tweak in Tweaks.ALL) {
            switches[tweak.id]?.isChecked = on
        }
        suppressCallbacks = false

        Thread {
            val batch = mutableListOf<Triple<String, String, String>>()
            for (tweak in Tweaks.ALL) {
                for (w in tweak.writes) {
                    val value = if (on) w.onValue else w.offValue
                    if (value.isEmpty()) continue
                    batch.add(Triple(w.namespace, w.key, value))
                }
            }
            val readBacks = ShellRunner.putBatch(batch)

            var totalOk = 0
            var total = 0
            for (tweak in Tweaks.ALL) {
                var success = 0
                for (w in tweak.writes) {
                    val value = if (on) w.onValue else w.offValue
                    if (value.isEmpty()) continue
                    total++
                    val rb = readBacks["${w.namespace}/${w.key}"]
                    if (rb == value) {
                        success++
                        totalOk++
                    }
                }
                val line = "${tweak.title}: ${if (on) "开启" else "关闭"} ($success/${tweak.writes.size})"
                mainHandler.post { log(line) }
            }
            mainHandler.post { log("=== 完成 ($totalOk/$total) ===") }
        }.start()
    }

    /** 单项开关：单键走 put，同样在后台线程执行。 */
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

    /**
     * 首次操作前备份所有键的原始值到应用私有目录。
     * 仅当备份文件不存在时写入，避免被优化后的值覆盖。
     */
    private fun backupOriginalValues() {
        try {
            val file = File(filesDir, "original_values.txt")
            if (file.exists()) return
            val sb = StringBuilder()
            for (tweak in Tweaks.ALL) {
                for (w in tweak.writes) {
                    val current = ShellRunner.get(w.namespace, w.key)
                    sb.append("${w.namespace}/${w.key}=$current\n")
                }
            }
            file.writeText(sb.toString())
            log("已备份原始值 (${file.absolutePath})")
        } catch (e: Exception) {
            log("备份失败: ${e.message}")
        }
    }

    private fun refreshAllStates() {
        Thread {
            for (tweak in Tweaks.ALL) {
                val first = tweak.writes.firstOrNull() ?: continue
                val current = ShellRunner.get(first.namespace, first.key)
                val isOn = current == first.onValue
                mainHandler.post {
                    suppressCallbacks = true
                    switches[tweak.id]?.isChecked = isOn
                    suppressCallbacks = false
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
