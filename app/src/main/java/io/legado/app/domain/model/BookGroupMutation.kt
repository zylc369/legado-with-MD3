package io.legado.app.domain.model

data class NewBookGroup(
    val groupName: String,
    val bookSort: Int,
    val enableRefresh: Boolean,
    val cover: String?,
    val pattern: String?,
)

data class BookGroupUpdate(
    val groupId: Long,
    val groupName: String,
    val cover: String?,
    val order: Int,
    val enableRefresh: Boolean,
    val show: Boolean,
    val bookSort: Int,
)

data class TagGroupRuleUpdate(
    val id: Long,
    val pattern: String,
    val groupName: String,
    val order: Int,
)
