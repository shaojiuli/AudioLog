package com.audiostats.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 各厂商的后台限制适配：国产系统在系统电池优化之外还有自己的「自启动 / 启动管理」，
 * 不打开的话通知监听服务会在后台被杀，导致漏记。这里按品牌给出说明和对应设置页。
 *
 * 设置页的类名来自各家系统管家，系统版本不同可能变化，所以逐个尝试，都失败时退回应用详情页。
 */
object VendorSettings {
    enum class Brand(val label: String) {
        HUAWEI("华为"), HONOR("荣耀"), XIAOMI("小米"), OPPO("OPPO"), VIVO("vivo"), SAMSUNG("三星"), OTHER("")
    }

    val brand: Brand by lazy {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        when {
            "honor" in m -> Brand.HONOR
            "huawei" in m -> Brand.HUAWEI
            "xiaomi" in m || "redmi" in m || "poco" in m -> Brand.XIAOMI
            "oppo" in m || "realme" in m || "oneplus" in m -> Brand.OPPO
            "vivo" in m || "iqoo" in m -> Brand.VIVO
            "samsung" in m -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }

    /** 设置页里「自启动」一行的标题 */
    val autoStartTitle: String
        get() = when (brand) {
            Brand.HUAWEI, Brand.HONOR -> "应用启动管理"
            Brand.SAMSUNG -> "电池与后台"
            else -> "自启动"
        }

    /** 要用户在厂商设置里做的事 */
    val autoStartHint: String
        get() = when (brand) {
            Brand.HUAWEI, Brand.HONOR -> "找到本应用，关闭「自动管理」，在弹窗里打开自启动、关联启动、后台活动"
            Brand.XIAOMI -> "打开本应用的「自启动」；另在应用详情里把「省电策略」设为无限制"
            Brand.OPPO -> "允许本应用自启动；另在电池设置里允许后台运行"
            Brand.VIVO -> "允许本应用后台高耗电 / 自启动"
            Brand.SAMSUNG -> "把本应用设为「不受限制」，并从「深度睡眠应用」中移除"
            Brand.OTHER -> "在系统设置里允许本应用自启动、后台运行"
        }

    /** 首页电池提醒里关于厂商设置的一句话 */
    val noticeHint: String
        get() = when (brand) {
            Brand.HUAWEI, Brand.HONOR -> "华为/荣耀还需要在「应用启动管理」里改为手动管理，打开自启动、关联启动和后台活动。"
            Brand.XIAOMI -> "小米的「省电策略：无限制」是另一项设置，检测不到，已设置过可以不再提示。"
            Brand.OTHER -> "部分系统还有单独的自启动开关，可在设置里找到。"
            else -> "${brand.label}还需要在系统里允许本应用自启动，已设置过可以不再提示。"
        }

    private fun candidates(): List<ComponentName> = when (brand) {
        Brand.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Brand.HONOR -> listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Brand.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Brand.OPPO -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        )
        Brand.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Brand.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Brand.OTHER -> emptyList()
    }

    /** 打开厂商的自启动 / 启动管理页；都打不开时退回本应用的详情页 */
    fun openAutoStart(context: Context) {
        for (c in candidates()) {
            try {
                context.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // 这个系统版本没有这个页面，试下一个
            } catch (e: SecurityException) {
                // 页面没有对外开放
            }
        }
        openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
