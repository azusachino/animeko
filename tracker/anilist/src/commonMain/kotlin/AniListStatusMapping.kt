/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/**
 * [UnifiedCollectionType] -> AniList `MediaListStatus`. `NOT_COLLECTED` 没有对应远程状态
 * (代表"从未收藏", AniList 侧没有"解除收藏"以外的等价状态), 返回 null 表示不应发起同步.
 */
fun UnifiedCollectionType.toAniListStatus(): String? = when (this) {
    UnifiedCollectionType.WISH -> "PLANNING"
    UnifiedCollectionType.DOING -> "CURRENT"
    UnifiedCollectionType.DONE -> "COMPLETED"
    UnifiedCollectionType.ON_HOLD -> "PAUSED"
    UnifiedCollectionType.DROPPED -> "DROPPED"
    UnifiedCollectionType.NOT_COLLECTED -> null
}

/**
 * AniList `MediaListStatus` -> [UnifiedCollectionType]. 未知/未来新增的远程状态返回 null 而不是抛异常,
 * 因为这是解析外部数据, 调用方应当把 null 当作"跳过, 保持本地状态"处理.
 */
fun String.toUnifiedCollectionTypeFromAniList(): UnifiedCollectionType? = when (this) {
    "PLANNING" -> UnifiedCollectionType.WISH
    "CURRENT" -> UnifiedCollectionType.DOING
    "COMPLETED" -> UnifiedCollectionType.DONE
    "PAUSED" -> UnifiedCollectionType.ON_HOLD
    "DROPPED" -> UnifiedCollectionType.DROPPED
    else -> null
}
