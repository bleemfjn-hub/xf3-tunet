package com.xf3.tuner

import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

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
 * Shizuku.newProcess 在 13.x 中为 @RestrictTo 私有方法，
 * 这里通过反射调用，避免编译期可见性问题。
 */
object ShellRunner {

    data class Result(val ok: Boolean, val output: String)

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

    /** 通过 Shizuku 以 shell 身份执行命令。 */
    fun exec(cmd: String): Result {
        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", cmd))
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            Result(process.exitValue() == 0, out + err)
        } catch (e: Exception) {
            Result(false, e.message ?: "unknown error")
        }
    }

    /**
     * 写入一个 Settings 键。
     * 策略：在【同一个 shell 进程】内执行 delete → insert → 读回验证。
     * 用 `;` 串联，保证顺序执行；读回值用 marker 包裹便于解析。
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

    /** 读取一个 Settings 键的当前值。 */
    fun get(namespace: String, key: String): String {
        val res = exec("settings get $namespace $key")
        return res.output.trim()
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
