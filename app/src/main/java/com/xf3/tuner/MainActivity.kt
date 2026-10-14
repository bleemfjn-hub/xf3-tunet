package com.xf3.tuner

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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var logView: TextView
    private lateinit var deviceStatus: TextView
    private val switches = mutableMapOf<String, Switch>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionRequestCode = 1001

    /** 单一串行执行队列：所有写入操作（单项 + 批量）都提交到这里，杜绝并发交错。 */
    private val executor = Executors.newSingleThreadExecutor()

    /** 批量操作进行中标志，期间拒绝新的单项操作。 */
    @Volatile
    private var busy = false

    /** 抑制开关回调（批量设置 UI 状态时使用）。 */
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
        findViewById<Button>(R.id.btnRevertAll).setOnClickListener { revertAll() }

        checkEnvironment()
    }

    override fun onResume() {
        super.onResume()
        // A1/A2: 切回前台时重新读取状态，反映系统回写
        if (firstResumeDone && !busy && Shizuku.pingBinder()) {
            refreshAllStates()
        }
        firstResumeDone = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        // C3: 仅在真正结束时关闭 executor（旋转屏幕重建时不关）
        if (isFinishing) {
            executor.shutdownNow()
        }
    }

    // ---------- 环境检查 ----------

    /** 返回 null 表示环境 OK，否则返回阻止原因。 */
    private fun checkEnvironmentBlocking(): String? {
        if (!ShellRunner.isTargetDevice()) {
            return "设备不匹配：当前为 ${android.os.Build.MODEL}，仅支持 vivo X Fold3 (V2303A)"
        }
        if (!Shizuku.pingBinder()) {
            return "Shizuku 未运行，请先启动 Shizuku"
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Shizuku 权限未授予"
        }
        return null
    }

    private fun checkEnvironment() {
        val model = android.os.Build.MODEL
        deviceStatus.text = "设备: $model | 目标机型: V2303A"

        val block = checkEnvironmentBlocking()
        if (block != null) {
            log("⚠ $block")
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                log("请求 Shizuku 权限...")
                Shizuku.requestPermission(permissionRequestCode)
            }
            return
        }
        log("Shizuku 就绪")
        refreshAllStates()
    }

    // ---------- UI 构建 ----------

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
            sw.isChecked = false
            sw.isEnabled = false
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (suppressCallbacks) return@setOnCheckedChangeListener
                applyTweak(tweak, isChecked)
            }
            container.addView(sw)
            switches[tweak.id] = sw
        }
    }

    // ---------- 一键优化 ----------

    /**
     * 一键优化：仅应用 defaultOn=true 的项（跳过 RISKY）。
     * 先备份原始值，再批量写入，最后逐项报告。
     */
    private fun applyAll(on: Boolean) {
        if (busy) { log("操作进行中，请稍候"); return }
        val block = checkEnvironmentBlocking()
        if (block != null) { log("⚠ 已阻止: $block"); return }

        val targets = Tweaks.ALL.filter { it.defaultOn }
        log("=== 一键优化 (${targets.size} 项) ===")

        busy = true
        executor.execute {
            try {
                // 1. 备份（仅当备份不存在）
                val backup = backupOriginalValues()
                if (!backup) {
                    mainHandler.post { log("⚠ 备份失败，已中止，未做任何修改") }
                    return@execute
                }

                // 2. 生成写入计划
                val batch = mutableListOf<Triple<String, String, String>>()
                for (tweak in targets) {
                    for (w in tweak.writes) {
                        if (w.onValue.isEmpty()) continue
                        batch.add(Triple(w.namespace, w.key, w.onValue))
                    }
                }

                // 3. 批量写入（单进程）
                val readBacks = ShellRunner.putBatch(batch)

                // 4. 逐项报告
                var totalOk = 0
                var total = 0
                for (tweak in targets) {
                    var success = 0
                    var expected = 0
                    for (w in tweak.writes) {
                        if (w.onValue.isEmpty()) continue
                        expected++
                        total++
                        if (readBacks["${w.namespace}/${w.key}"] == w.onValue) {
                            success++
                            totalOk++
                        }
                    }
                    val line = "${tweak.title}: ($success/$expected)"
                    mainHandler.post { log(line) }
                }
                mainHandler.post { log("=== 完成 ($totalOk/$total) ===") }
            } finally {
                busy = false
                // Settings 缓存需要短暂时间落盘，延迟后再读回，避免读到旧值
                try { Thread.sleep(500) } catch (_: InterruptedException) { }
                refreshAllStatesInternal()
            }
        }
    }

    // ---------- 真还原 ----------

    /**
     * 全部还原：读取备份文件，逐键恢复原值；原本不存在的键则删除。
     * 不使用 offValue。
     */
    private fun revertAll() {
        if (busy) { log("操作进行中，请稍候"); return }
        val block = checkEnvironmentBlocking()
        if (block != null) { log("⚠ 已阻止: $block"); return }

        val file = File(filesDir, "original_values.txt")
        if (!file.exists()) {
            log("⚠ 无备份文件，无法还原")
            return
        }

        log("=== 全部还原（从备份）===")
        busy = true
        executor.execute {
            try {
                val lines = file.readLines()
                val toRestore = mutableListOf<Triple<String, String, String>>()
                val toDelete = mutableListOf<Pair<String, String>>()

                for (line in lines) {
                    if (line.isBlank()) continue
                    val idx = line.indexOf('=')
                    if (idx <= 0) continue
                    val path = line.substring(0, idx)          // namespace/key
                    val value = line.substring(idx + 1)        // 原值，"__ABSENT__" 表示原本不存在
                    val slash = path.indexOf('/')
                    if (slash <= 0) continue
                    val ns = path.substring(0, slash)
                    val k = path.substring(slash + 1)
                    if (value == "__ABSENT__") {
                        toDelete.add(ns to k)
                    } else {
                        toRestore.add(Triple(ns, k, value))
                    }
                }

                // 恢复存在的键
                val readBacks = ShellRunner.putBatch(toRestore)
                var ok = 0
                for ((ns, k, v) in toRestore) {
                    if (readBacks["$ns/$k"] == v) ok++
                }
                mainHandler.post { log("恢复 $ok/${toRestore.size} 项") }

                // 删除原本不存在的键
                var delOk = 0
                for ((ns, k) in toDelete) {
                    val res = ShellRunner.delete(ns, k)
                    if (res.ok) delOk++
                }
                if (toDelete.isNotEmpty()) {
                    mainHandler.post { log("删除原本不存在的键 $delOk/${toDelete.size} 项") }
                }
                mainHandler.post { log("=== 还原完成 ===") }
            } finally {
                busy = false
                refreshAllStatesInternal()
            }
        }
    }

    // ---------- 单项开关 ----------

    private fun applyTweak(tweak: Tweak, on: Boolean) {
        if (busy) { log("操作进行中，请稍候"); return }
        val block = checkEnvironmentBlocking()
        if (block != null) { log("⚠ 已阻止: $block"); return }

        busy = true
        executor.execute {
            try {
                var success = 0
                for (w in tweak.writes) {
                    val value = if (on) w.onValue else w.offValue
                    if (value.isEmpty()) continue
                    val res = ShellRunner.put(w.namespace, w.key, value)
                    if (res.ok) success++
                }
                val line = "${tweak.title}: ${if (on) "开启" else "关闭"} ($success/${tweak.writes.size})"
                mainHandler.post { log(line) }
            } finally {
                busy = false
                refreshAllStatesInternal()
            }
        }
    }

    // ---------- 备份 ----------

    /**
     * 备份所有键的原始值。
     * - 原本不存在的键记录为 __ABSENT__
     * - 原子写入（先写临时文件再 rename），避免半成品文件
     * - 返回是否成功
     */
    private fun backupOriginalValues(): Boolean {
        val file = File(filesDir, "original_values.txt")
        if (file.exists()) return true
        return try {
            val sb = StringBuilder()
            for (tweak in Tweaks.ALL) {
                for (w in tweak.writes) {
                    val current = ShellRunner.get(w.namespace, w.key)
                    sb.append("${w.namespace}/${w.key}=").append(current ?: "__ABSENT__").append("\n")
                }
            }
            val tmp = File(filesDir, "original_values.txt.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(sb.toString())
                tmp.delete()
            }
            mainHandler.post { log("已备份原始值") }
            true
        } catch (e: Exception) {
            mainHandler.post { log("备份失败: ${e.message}") }
            false
        }
    }

    // ---------- 状态刷新（三态） ----------

    /**
     * 刷新所有开关状态。
     * 依据【全部相关键】判定：
     * - 全部等于 onValue → 开
     * - 全部等于 offValue → 关
     * - 其他 → 关闭并标注（部分生效）
     */
    private fun refreshAllStates() {
        executor.execute { refreshAllStatesInternal() }
    }

    /** 实际刷新逻辑。只能从 executor 线程内调用，禁止再向 executor 提交任务（会死锁）。 */
    private fun refreshAllStatesInternal() {
        run {
            for (tweak in Tweaks.ALL) {
                var onCount = 0
                var offCount = 0
                var validCount = 0
                for (w in tweak.writes) {
                    val current = ShellRunner.get(w.namespace, w.key)
                    validCount++
                    if (current == w.onValue) onCount++
                    // 键不存在（null）视为「关」；offValue 为空的键不参与 off 判定
                    if (current == null) {
                        offCount++
                    } else if (w.offValue.isNotEmpty() && current == w.offValue) {
                        offCount++
                    }
                }
                val state = when {
                    validCount == 0 -> 0
                    onCount == validCount -> 1
                    offCount == validCount -> 0
                    else -> -1   // 部分生效
                }
                val riskTag = when (tweak.risk) {
                    Risk.SAFE -> ""
                    Risk.CAUTION -> " [发热↑]"
                    Risk.RISKY -> " [高风险]"
                }
                val label = if (state == -1) {
                    tweak.title + riskTag + " [部分生效]\n" + tweak.desc
                } else {
                    tweak.title + riskTag + "\n" + tweak.desc
                }
                mainHandler.post {
                    suppressCallbacks = true
                    val sw = switches[tweak.id]
                    sw?.isEnabled = true
                    sw?.isChecked = state == 1
                    sw?.text = label
                    suppressCallbacks = false
                }
            }
            mainHandler.post { log("状态已刷新") }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
        }
    }
}
