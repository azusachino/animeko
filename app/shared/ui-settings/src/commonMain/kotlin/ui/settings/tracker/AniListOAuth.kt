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
 * TODO: 这个 fork 还没有在 https://anilist.co/settings/developer 注册自己的 AniList OAuth application,
 * 所以这里先用一个占位 client_id. 在真正让用户使用这个登录流程之前, 必须换成注册后拿到的真实 client_id
 * (client_id 本身是公开的, 写在客户端代码里没有安全问题, 但占位值显然打不开真实的 AniList 授权页).
 */
internal const val ANILIST_OAUTH_CLIENT_ID = "TODO_REGISTER_ANILIST_OAUTH_APP"

internal val ANILIST_AUTHORIZE_URL =
    "https://anilist.co/api/v2/oauth/authorize?client_id=$ANILIST_OAUTH_CLIENT_ID&response_type=token"
