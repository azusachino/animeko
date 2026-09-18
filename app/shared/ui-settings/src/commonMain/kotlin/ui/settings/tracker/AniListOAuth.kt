/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tracker

/**
 * AniList Auth PIN 登录: 打开系统浏览器让用户在 AniList 官方页面授权, 授权页展示一个 token 让用户手动
 * 复制粘贴回 App -- 没有 redirect handling, 见 `docs/dev/trackers/anilist-sync-plan.md` 4.1.
 *
 * 对应 AniList OAuth application (https://anilist.co/settings/developer) 的 redirect URL 需设为
 * `https://anilist.co/api/v2/oauth/pin` -- AniList 自带的展示页, 不指向本项目的任何地址.
 * client_id 本身是公开的, 写在客户端代码里没有安全问题.
 */
internal const val ANILIST_OAUTH_CLIENT_ID = "51393"

internal val ANILIST_AUTHORIZE_URL =
    "https://anilist.co/api/v2/oauth/authorize?client_id=$ANILIST_OAUTH_CLIENT_ID&response_type=token"
