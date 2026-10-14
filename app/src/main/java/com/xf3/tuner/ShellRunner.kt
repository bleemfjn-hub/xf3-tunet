package com.xf3.tuner

import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 核心写入器。
 *
 * 实测结论（vivo X Fold3 / OriginOS 16.2）：
 * - `settings put`        → 被拦截，无效
 * - `content update`      → 被拦截，静默失败（返回 0 但值不变）
 * - `content delete`+`content insert` → 有效
 *
 * 关键约束：delete / insert / 读回验证必须在【同一个 shell 进程】内连续执行。
 * 若拆成多次 Shizuku 进程调用，OriginOS 的 SettingsProvider 会因事务隔离导致写入丢失。
 *
 * stdout / stderr 必须并行读取，否则大输出时 stderr 缓冲区填满会导致死锁。
 *
 * Shizuku.newProcess 在 13.x 中为 @RestrictTo 私有方法，
 * 这里通过反射调用；官方推荐迁移到 UserService，但本场景为一次性 shell 命令，
 * UserService 需独立进程 + AIDL，成本更高，故保留反射并加异常兜底。
 */
object ShellRunner {

    /** 命令执行结果。stdout 与 stderr 分离保存。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /** 单键写入结果。 */
    data class Result(val ok: Boolean, val output: String)

    private const val TIMEOUT_SECONDS = 10L

    /** namespace 白名单，防止拼接任意 URI。 */
    private val ALLOWED_NAMESPACES = setOf("secure", "system", "global")

    /** 通过反射调用 Shizuku.newProcess，返回 Process 对象。 */
    private fun newShizukuProcess(cmd: Array<String>): Process {
        val clazz = Shizuku::class.java
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }

    /**
     * 通过 Shizuku 以 shell 身份执行命令。
     * stdout / stderr 用独立线程并行读取；超时后强制终止并等待退出。
     */
    fun exec(cmd: String): ExecResult {
        var process: Process? = null
        try {
            process = newShizukuProcess(arrayOf("sh", "-c", cmd))

            val outBuilder = StringBuilder()
            val errBuilder = StringBuilder()

            val outThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                        .let { outBuilder.append(it) }
                } catch (_: Exception) { }
            }
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                        .let { errBuilder.append(it) }
                } catch (_: Exception) { }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) { }
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                return ExecResult(-1, outBuilder.toString(), errBuilder.toString(), true)
            }
            outThread.join(2000)
            errThread.join(2000)

            return ExecResult(
                process.exitValue(),
                outBuilder.toString(),
                errBuilder.toString(),
                false
            )
        } catch (e: Exception) {
            try { process?.destroyForcibly() } catch (_: Exception) { }
            return ExecResult(-1, "", e.message ?: "unknown error", false)
        }
    }

    /**
     * 写入一个 Settings 键。
     * 策略：在【同一个 shell 进程】内执行 delete → insert → 读回验证。
     * value 经单引号包裹 + 内部单引号转义，避免 Shell 语法解释。
     */
    fun put(namespace: String, key: String, value: String): Result {
        if (namespace !in ALLOWED_NAMESPACES) {
            return Result(false, "invalid namespace: $namespace")
        }
        val uri = "content://settings/$namespace"
        val escKey = shellSingleQuote(key)
        val escValue = shellSingleQuote(value)
        val cmd = buildString {
            append("content delete --uri $uri --where \"name=$escKey\" 2>&1; ")
            append("content insert --uri $uri --bind name:s:$escKey --bind value:s:$escValue 2>&1; ")
            append("settings get $namespace $key")
        }
        val res = exec(cmd)
        val readBack = res.stdout.trim().lines().lastOrNull()?.trim() ?: ""
        val ok = readBack == value
        return Result(ok, "readBack=$readBack stderr=${res.stderr.trim()}")
    }

    /**
     * 批量写入：把多个键合并成【一条】sh -c 命令，只创建一次 Shizuku 进程。
     * 每个键写入后立即读回，用 `@@` 前缀 + 序号标记，避免值中换行破坏解析。
     * 返回：key("namespace/key") -> 读回值（null 表示未读到）
     */
    fun putBatch(writes: List<Triple<String, String, String>>): Map<String, String?> {
        if (writes.isEmpty()) return emptyMap()
        val valid = writes.filter { it.first in ALLOWED_NAMESPACES }
        if (valid.isEmpty()) return emptyMap()

        val cmd = buildString {
            valid.forEachIndexed { index, (namespace, key, value) ->
                val uri = "content://settings/$namespace"
                val escKey = shellSingleQuote(key)
                val escValue = shellSingleQuote(value)
                append("content delete --uri $uri --where \"name=$escKey\" 2>&1; ")
                append("content insert --uri $uri --bind name:s:$escKey --bind value:s:$escValue 2>&1; ")
                append("printf '@@%d=%s\\n' $index \"\$(settings get $namespace $key)\"; ")
            }
        }
        val res = exec(cmd)

        // 解析：@@<index>=<value>
        val byIndex = mutableMapOf<Int, String>()
        for (line in res.stdout.lines()) {
            val t = line.trim()
            if (!t.startsWith("@@")) continue
            val body = t.removePrefix("@@")
            val idx = body.indexOf('=')
            if (idx <= 0) continue
            val i = body.substring(0, idx).toIntOrNull() ?: continue
            byIndex[i] = body.substring(idx + 1)
        }

        val result = mutableMapOf<String, String?>()
        valid.forEachIndexed { index, (namespace, key, _) ->
            result["$namespace/$key"] = byIndex[index]
        }
        return result
    }

    /** 读取一个 Settings 键的当前值。返回 null 表示键不存在。 */
    fun get(namespace: String, key: String): String? {
        if (namespace !in ALLOWED_NAMESPACES) return null
        val res = exec("settings get $namespace $key")
        val v = res.stdout.trim().lines().lastOrNull()?.trim() ?: ""
        // settings get 对不存在的键返回字符串 "null"
        return if (v == "null" || v.isEmpty()) null else v
    }

    /** 删除一个 Settings 键。 */
    fun delete(namespace: String, key: String): Result {
        if (namespace !in ALLOWED_NAMESPACES) {
            return Result(false, "invalid namespace: $namespace")
        }
        val uri = "content://settings/$namespace"
        val escKey = shellSingleQuote(key)
        val res = exec("content delete --uri $uri --where \"name=$escKey\" 2>&1")
        return Result(res.ok, res.stdout + res.stderr)
    }

    /** 用单引号包裹字符串，内部单引号转义为 '\''。 */
    private fun shellSingleQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /** 检查 Shizuku 是否可用。 */
    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 验证设备是否为 vivo X Fold3。 */
    fun isTargetDevice(): Boolean {
        return Build.MODEL == "V2303A" || Build.MODEL.contains("V2303A")
    }
}
