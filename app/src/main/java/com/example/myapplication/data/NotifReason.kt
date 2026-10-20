package com.example.myapplication.data

/**
 * 通知被移除的**原因**（`onNotificationRemoved(..., reason)`）。
 *
 * 权威来源：本机 SDK 源码
 *   `…/Sdk/sources/android-37.0/android/service/notification/NotificationListenerService.java`
 *   —— 注释是照抄的，不是我理解的
 *
 *最要紧的一条：**`REASON_APP_CANCEL = 8`**
 *   > "Notification was canceled by the **app** canceling this specific notification."
 *   => **由那个 App 自己取消了这一条** —— **微信/QQ 撤回消息走的就是它**
 *   （实测吻合：抓到的那次撤回就是 `REMOVED … reason=8`）
 */
object NotifReason {

    /** 给界面用的中文标签（英文常量名保留 => 方便对 SDK 原文） */
    fun label(reason: Int): String = when (reason) {
        1 -> "CLICK 用户点了它"
        2 -> "CANCEL 用户把它划掉/清掉"
        3 -> "CANCEL_ALL 用户一键清空"
        4 -> "ERROR 通知展开出错"
        5 -> "PACKAGE_CHANGED 应用被更新/改动"
        6 -> "USER_STOPPED 用户被停用"
        7 -> "PACKAGE_BANNED 应用被禁用"
        8 -> "APP_CANCEL 应用自己取消了这条（撤回走这里）"
        9 -> "APP_CANCEL_ALL 应用清掉了自己的全部通知"
        10 -> "LISTENER_CANCEL 监听侧取消"
        11 -> "LISTENER_CANCEL_ALL 监听侧清空"
        12 -> "GROUP_SUMMARY_CANCELED 分组摘要被取消"
        13 -> "GROUP_OPTIMIZATION 分组优化"
        14 -> "PACKAGE_SUSPENDED 应用被挂起"
        15 -> "PROFILE_TURNED_OFF 工作资料关闭"
        16 -> "UNAUTOBUNDLED 解除自动分组"
        17 -> "CHANNEL_BANNED 通知渠道被禁用"
        18 -> "SNOOZED 被延后"
        19 -> "TIMEOUT 超时自动消失"
        20 -> "CHANNEL_REMOVED 渠道被删除"
        21 -> "CLEAR_DATA 数据被清"
        22 -> "ASSISTANT_CANCEL 助手取消"
        23 -> "LOCKDOWN 锁定模式"
        24 -> "BUNDLE_DISMISSED 捆绑组被关掉"
        else -> "未知($reason)"
    }

    /** 是不是"应用自己取消的"（8 / 9）—— 撤回最强的系统层信号 */
    fun isAppCancel(reason: Int): Boolean = reason == 8 || reason == 9
}
