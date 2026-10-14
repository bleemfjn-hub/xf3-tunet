package com.xf3.tuner

import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 核心写入器。
 *
 * 关键：OriginOS 会拦截 shell 的 `settings put` 命令，
 * 但 `content insert/update/delete` 走 ContentProvider binder，可以写入。
 * 所以所有写操作都通过 content 命令完成。
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

    /** 写入一个 Settings 键（自动处理 insert / update）。 */
    fun put(namespace: String, key: String, value: String): Result {
        val uri = "content://settings/$namespace"
        // 先尝试 update（键已存在时）
        val updateCmd = "content update --uri $uri --bind value:s:'$value' --where \"name='$key'\""
        val updateRes = exec(updateCmd)
        if (updateRes.ok && !updateRes.output.contains("Updated 0 rows")) {
            return updateRes
        }
        // update 没改到行，说明键不存在，改用 insert
        val insertCmd = "content insert --uri $uri --bind name:s:'$key' --bind value:s:'$value'"
        return exec(insertCmd)
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
