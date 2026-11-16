package io.github.acedroidx.frp

import android.content.Context
import java.io.File

/**
 * 隧道启动器类型。
 *
 * FRPC/FRPS 使用 frp 的 TOML 配置文件；
 * MEFRP 使用 MeFrp 提供的 mefrpc 客户端（token + 端口）；
 * CLOUDFLARE 使用 cloudflared 的 tunnel token。
 */
enum class FrpType(val typeName: String) {
    FRPC("frpc"), FRPS("frps"), MEFRP("mefrp"), CLOUDFLARE("cloudflare");

    fun getDir(context: Context): File {
        return File(context.filesDir, this.typeName)
    }

    fun getLibName(): String {
        return when (this) {
            FRPC -> BuildConfig.FrpcFileName
            FRPS -> BuildConfig.FrpsFileName
            MEFRP -> BuildConfig.MefrpcFileName
            CLOUDFLARE -> BuildConfig.CloudflaredFileName
        }
    }

    fun getAutoStartPreferencesKey(): String {
        return when (this) {
            FRPC -> PreferencesKey.AUTO_START_FRPC_LIST
            FRPS -> PreferencesKey.AUTO_START_FRPS_LIST
            MEFRP -> PreferencesKey.AUTO_START_MEFRP_LIST
            CLOUDFLARE -> PreferencesKey.AUTO_START_CLOUDFLARE_LIST
        }
    }

    fun getConfigAssetsName(): String {
        return when (this) {
            FRPC -> BuildConfig.FrpcConfigFileName
            FRPS -> BuildConfig.FrpsConfigFileName
            // MeFrp / Cloudflare 的配置由用户直接输入，不使用内置模板
            MEFRP, CLOUDFLARE -> ""
        }
    }

    /**
     * 是否使用 frp 原生的 `-c <config>` 启动方式。
     */
    fun isFrp(): Boolean {
        return this == FRPC || this == FRPS
    }

    fun getDisplayName(context: Context): String {
        return when (this) {
            FRPC -> context.getString(R.string.frpc_label)
            FRPS -> context.getString(R.string.frps_label)
            MEFRP -> context.getString(R.string.mefrp_label)
            CLOUDFLARE -> context.getString(R.string.cloudflare_label)
        }
    }
}
