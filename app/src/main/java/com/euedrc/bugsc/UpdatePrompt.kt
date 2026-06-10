package com.euedrc.bugsc

/** 启动自动检查更新时，决定是否弹新版本提示窗 */
object UpdatePrompt {

    /**
     * @param ignored 用户点过「忽略此版本」的版本号，null 表示没忽略过
     */
    fun shouldPrompt(current: String, remote: String, ignored: String?): Boolean =
        AppUpdateClient.isNewerVersion(current, remote) && remote != ignored
}
