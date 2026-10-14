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
 * 因此 put() 把三步合并为一条 sh -c 命令。
 *
 * stdout / stderr 必须并行读取，否则大输出时 stderr 缓冲区填满会导致死锁。
 *
 * Shizuku.newProcess 在 13.x 中为 @RestrictTo 私有方法，
 * 这里通过反射调用，避免编译期可见性问题。
 */
object ShellRunner {

    data class Result(val ok: Boolean, val output: String)

    private const val TIMEOUT_SECONDS = 10L

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
     * stdout / stderr 用独立线程并行读取，避免管道缓冲区死锁；
     * 整体加超时，防止命令挂死。
     */
    fun exec(cmd: String): Result {
        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", cmd))

            val outBuilder = StringBuilder()
            val errBuilder = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    outBuilder.append(reader.readText())
                }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    errBuilder.append(reader.readText())
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return Result(false, "timeout after ${TIMEOUT_SECONDS}s")
            }
            outThread.join(1000)
            errThread.join(1000)

            Result(process.exitValue() == 0, outBuilder.toString() + errBuilder.toString())
        } catch (e: Exception) {
            Result(false, e.message ?: "unknown error")
        }
    }

    /**
     * 写入一个 Settings 键。
     * 策略：在【同一个 shell 进程】内执行 delete → insert → 读回验证。
     * 用 `;` 串联，保证顺序执行；读回值取最后一行。
     */
    fun put(namespace: String, key: String, value: String): Result {
        val uri = "content://settings/$namespace"
        val cmd = buildString {
            append("content delete --uri $uri --where \"name='$key'\" 2>/dev/null; ")
            append("content insert --uri $uri --bind name:s:$key --bind value:s:$value 2>/dev/null; ")
            append("settings get $namespace $key")
        }
        val res = exec(cmd)
        val readBack = res.output.trim().lines().lastOrNull()?.trim() ?: ""
        val ok = readBack == value
        return Result(ok, "readBack=$readBack")
    }

    /**
     * 批量写入：把多个键合并成【一条】sh -c 命令，只创建一次 Shizuku 进程。
     * 返回每个键的读回值，key 为 "namespace/key"。
     */
    fun putBatch(writes: List<Triple<String, String, String>>): Map<String, String> {
        if (writes.isEmpty()) return emptyMap()
        val cmd = buildString {
            for ((namespace, key, value) in writes) {
                val uri = "content://settings/$namespace"
                append("content delete --uri $uri --where \"name='$key'\" 2>/dev/null; ")
                append("content insert --uri $uri --bind name:s:$key --bind value:s:$value 2>/dev/null; ")
                append("echo \"@@$namespace/$key=\$(settings get $namespace $key)\"; ")
            }
        }
        val res = exec(cmd)
        val result = mutableMapOf<String, String>()
        for (line in res.output.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("@@")) continue
            val body = trimmed.removePrefix("@@")
            val idx = body.indexOf('=')
            if (idx <= 0) continue
            result[body.substring(0, idx)] = body.substring(idx + 1).trim()
        }
        return result
    }

    /** 读取一个 Settings 键的当前值。 */
    fun get(namespace: String, key: String): String {
        val res = exec("settings get $namespace $key")
        return res.output.trim().lines().lastOrNull()?.trim() ?: ""
    }

    /** 删除一个 Settings 键。 */
    fun delete(namespace: String, key: String): Result {
        val uri = "content://settings/$namespace"
        return exec("content delete --uri $uri --where \"name='$key'\"")
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
