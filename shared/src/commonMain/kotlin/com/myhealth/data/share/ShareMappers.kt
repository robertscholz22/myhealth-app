package com.myhealth.data.share

import com.myhealth.data.db.entity.ShareConfigEntity
import com.myhealth.data.db.entity.ShareLinkEntity
import com.myhealth.domain.repository.ShareSetup
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareLink

fun ShareLinkEntity.toDomain(): ShareLink? {
    val k = ShareKind.entries.firstOrNull { it.name == kind } ?: return null
    return ShareLink(
        kind = k, localKey = localKey, shareId = shareId, authorId = authorId, authorName = authorName,
        version = version, sharedAtMillis = sharedAtMillis, payloadHash = payloadHash, remoteGone = remoteGone,
        basedOnShareId = basedOnShareId, basedOnName = basedOnName, basedOnAuthor = basedOnAuthor,
    )
}

internal fun ShareLink.toEntity(): ShareLinkEntity = ShareLinkEntity(
    kind = kind.name, localKey = localKey, shareId = shareId, authorId = authorId, authorName = authorName,
    version = version, sharedAtMillis = sharedAtMillis, payloadHash = payloadHash, remoteGone = remoteGone,
    basedOnShareId = basedOnShareId, basedOnName = basedOnName, basedOnAuthor = basedOnAuthor,
)

internal fun ShareConfigEntity.toSetup(): ShareSetup = ShareSetup(projectId, apiKey, joinCode, uid, displayName)
