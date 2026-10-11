package com.opautocloud.claim

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 更新检查。
 *
 * 查询 https://api.github.com/repos/KotobaWakusei/AutoCloudClaim/releases/latest，
 * 取 tag_name（形如 build-N），与本机构建号 BuildConfig.BUILD_NUMBER 比较。
 *
 * CI（.github/workflows/build.yml）在 main 分支 push 时创建 release，
 * tag 为 build-${{ github.run_number }}，并通过 -PbuildNumber=N 注入 BuildConfig。
 * 本地开发构建 BUILD_NUMBER=0，永远认为有更新（可在 UI 里关闭）。
 */
object UpdateChecker {

    private const val REPO = "KotobaWakusei/AutoCloudClaim"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val TIMEOUT_MS = 8_000

    data class Result(
        val hasUpdate: Boolean,
        val latestTag: String,
        val latestBuild: Int,
        val releaseUrl: String,
        val releaseName: String,
    )

    fun check(currentBuild: Int): Result {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AutoCloudClaim")

            val code = conn.responseCode
            if (code != 200) {
                return Result(false, "", 0, RELEASES_URL, "")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val htmlUrl = json.optString("html_url", RELEASES_URL)
            val latestBuild = parseBuildNumber(tagName)

            return Result(
                hasUpdate = latestBuild > currentBuild,
                latestTag = tagName,
                latestBuild = latestBuild,
                releaseUrl = htmlUrl,
                releaseName = releaseName,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 从 "build-47" 提取 47；解析失败返回 0。 */
    private fun parseBuildNumber(tag: String): Int {
        val n = tag.removePrefix("build-").trim()
        return n.toIntOrNull() ?: 0
    }

    fun openReleasePage(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
