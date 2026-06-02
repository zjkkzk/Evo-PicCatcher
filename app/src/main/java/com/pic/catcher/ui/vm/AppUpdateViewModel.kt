package com.pic.catcher.ui.vm

import android.content.Context
import com.lu.magic.util.AppUtil
import com.lu.magic.util.log.LogUtil
import com.pic.catcher.R
import com.pic.catcher.base.BaseViewModel
import com.pic.catcher.route.AppRouter
import com.pic.catcher.util.AppUpdateCheckUtil

class AppUpdateViewModel : BaseViewModel() {
    private var hasOnCheckAction = false
    fun checkOnEnter(context: Context) {
        if (hasOnCheckAction) {
            return
        }
        if (!AppUpdateCheckUtil.hasCheckFlagOnEnter()) {
            return
        }
        hasOnCheckAction = true

        AppUpdateCheckUtil.checkUpdate { url, name, err ->
            if (url.isBlank() || name.isBlank() || err != null) {
                hasOnCheckAction = false
                return@checkUpdate
            }
            // 自动打开浏览器下载，不再弹窗询问
            openBrowserDownloadUrl(context, url)
            hasOnCheckAction = false
        }
    }

    fun checkOnce(context: Context, fallBackText: String = AppUtil.getContext().getString(R.string.app_update_not_found)) {
        if (hasOnCheckAction) {
            return
        }
        hasOnCheckAction = true
        AppUpdateCheckUtil.checkUpdate { url, name, err ->
            if (url.isBlank() || name.isBlank()) {
                hasOnCheckAction = false
                return@checkUpdate
            }
            // 自动打开浏览器下载
            openBrowserDownloadUrl(context, url)
            hasOnCheckAction = false
        }
    }

    private fun openBrowserDownloadUrl(context: Context, url: String) {
        try {
            AppRouter.route(context, url)
        } catch (e: Exception) {
            LogUtil.w(e)
        }
    }

}