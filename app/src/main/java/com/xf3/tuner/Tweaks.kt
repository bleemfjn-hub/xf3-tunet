package com.xf3.tuner

/**
 * 每项优化 = 一组 Settings 键的写入。
 *
 * 所有键均在 vivo X Fold3 (V2303A / OriginOS 16.2) 上通过
 * `settings list secure|system|global` 实测确认真实存在。
 *
 * 风险等级：
 * - SAFE    仅影响体验，可随时还原
 * - CAUTION 影响功耗/发热，建议按需开启
 * - RISKY   可能影响系统稳定性，默认关闭
 */
data class KeyWrite(
    val namespace: String,
    val key: String,
    val onValue: String,
    val offValue: String
)

enum class Risk { SAFE, CAUTION, RISKY }

data class Tweak(
    val id: String,
    val title: String,
    val desc: String,
    val writes: List<KeyWrite>,
    val defaultOn: Boolean = true,
    val risk: Risk = Risk.SAFE
)

object Tweaks {

    val ALL: List<Tweak> = listOf(
        Tweak(
            id = "abe",
            title = "应用启动加速 (ABE)",
            desc = "开启 ABE 统一开关，App 冷启动更快",
            writes = listOf(
                KeyWrite("secure", "abe_unified_switch", "1", "0")
            )
        ),
        Tweak(
            id = "refresh",
            title = "锁定 120Hz 刷新率",
            desc = "强制高刷，滑动更跟手",
            writes = listOf(
                KeyWrite("secure", "refresh_rate_mode", "120", "60"),
                KeyWrite("global", "peak_refresh_rate", "120", "60"),
                KeyWrite("global", "min_refresh_rate", "120", "60")
            )
        ),
        Tweak(
            id = "thermal",
            title = "关闭游戏温控",
            desc = "游戏时不再因发热降频（会增加发热）",
            writes = listOf(
                KeyWrite("system", "vivo_gamethermal_switch", "0", "1"),
                KeyWrite("system", "HighTemptureProtect", "0", "1"),
                KeyWrite("system", "thermal_manager_enabled", "0", "1"),
                KeyWrite("global", "thermal_control_disabled", "1", "0"),
                KeyWrite("global", "vivo_thermal_policy", "0", "1")
            ),
            risk = Risk.CAUTION
        ),
        Tweak(
            id = "perf",
            title = "性能模式 (CPU 调度)",
            desc = "CPU 调度偏向性能，减少卡顿",
            writes = listOf(
                KeyWrite("system", "power_save_type_performance", "1", "0"),
                KeyWrite("global", "sys_uidcpupower", "Deadline", ""),
                KeyWrite("global", "GPUTUNER_SWITCH", "true", "false")
            ),
            risk = Risk.CAUTION
        ),
        Tweak(
            id = "touch",
            title = "触控优化 (SurfaceFlinger)",
            desc = "降低渲染延迟，触控响应更快",
            writes = listOf(
                KeyWrite("global", "debug.sf.high_fps_early_gl_phase_offset_ns", "1", "0"),
                KeyWrite("global", "debug.sf.latch_unsignaled", "1", "0"),
                KeyWrite("global", "debug.sf.enable_gl_backpressure", "1", "0"),
                KeyWrite("global", "debug.cpurend.vsync", "false", "true"),
                KeyWrite("global", "hwui.disable_vsync", "true", "false")
            ),
            risk = Risk.CAUTION
        ),
        Tweak(
            id = "anim",
            title = "动画缩放 0.5x",
            desc = "动画变快，操作更利落",
            writes = listOf(
                KeyWrite("global", "animator_duration_scale", "0.5", "1.0"),
                KeyWrite("global", "transition_animation_scale", "0.5", "1.0"),
                KeyWrite("global", "window_animation_scale", "0.5", "1.0")
            ),
            defaultOn = false
        ),
        Tweak(
            id = "smarttouch",
            title = "智能触控增强",
            desc = "开启 BBK 智能触控与分体手势滚动",
            writes = listOf(
                KeyWrite("system", "bbk_smart_touch_setting", "1", "0"),
                KeyWrite("system", "bbk_touch_settings", "1", "0"),
                KeyWrite("system", "bbk_seperate_hand_touch_scroll_setting", "1", "0")
            )
        ),
        Tweak(
            id = "chargebalance",
            title = "均衡充电 (护电池)",
            desc = "降低充电功率，延长电池寿命",
            writes = listOf(
                KeyWrite("system", "balance_charge_switch", "1", "0")
            )
        ),
        Tweak(
            id = "ambientwake",
            title = "息屏触摸唤醒",
            desc = "息屏时轻触屏幕即可唤醒（关闭省电）",
            writes = listOf(
                KeyWrite("global", "ambient_touch_to_wake", "1", "0")
            )
        ),
        Tweak(
            id = "interp",
            title = "游戏插帧",
            desc = "游戏画面插帧到高帧率（增加发热）",
            writes = listOf(
                KeyWrite("system", "gamecube_frame_interpolation", "1:1:0:72:144", "0:-1:0:0:0")
            ),
            defaultOn = false,
            risk = Risk.RISKY
        ),
        Tweak(
            id = "charge",
            title = "超级闪充",
            desc = "充电更快，但发热明显（折叠屏不建议）",
            writes = listOf(
                KeyWrite("secure", "vivo_keyguard_super_speed_charge_switch", "1", "-1")
            ),
            defaultOn = false,
            risk = Risk.RISKY
        )
    )
}
