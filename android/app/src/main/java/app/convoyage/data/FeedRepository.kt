package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.FeedPage
import app.convoyage.api.FeedScope
import app.convoyage.api.LikeState
import app.convoyage.api.Post

/** The read side of the feed; an interface so paging can be tested without a server. */
interface FeedApi {
    suspend fun feed(scope: FeedScope, before: String?, limit: Int = 20, lat: Double? = null, lng: Double? = null): ApiResult<FeedPage>
    suspend fun userPosts(handle: String, before: String?, limit: Int = 20): ApiResult<FeedPage>
}

/**
 * The three feeds (Everyone, Following, Nearby), each with its own [Pager], plus the signed-in person's own posts
 * for the profile. Likes and comment counts are changed in place in every list that holds the post.
 */
class FeedRepository(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val api: FeedApi,
    /** The last known position, for Nearby. Null = no fix yet. */
    private val location: () -> LatLngD?,
    private val pageSize: Int = 20,
) {
    private val pagers = FeedScope.entries.associateWith { which ->
        Pager<Post>(scope, { it.id }) { cursor ->
            if (which == FeedScope.Nearby) {
                val here = location() ?: return@Pager ApiResult.Err(0, "Waiting for your location")
                api.feed(which, cursor, pageSize, here.lat, here.lng).toPage()
            } else {
                api.feed(which, cursor, pageSize).toPage()
            }
        }
    }

    fun pager(which: FeedScope): Pager<Post> = pagers.getValue(which)

    /** A pager over one person's posts (profile). Not cached: the caller keeps it. */
    fun userPosts(handle: String): Pager<Post> =
        Pager(scope, { it.id }) { cursor -> api.userPosts(handle, cursor, pageSize).toPage() }

    fun applyLike(postId: String, state: LikeState) = everywhere(postId) { it.copy(likeCount = state.likeCount, likedByMe = state.likedByMe) }

    /** Optimistic flip before the server answers; [applyLike] then sets the real counts, or [revertLike] undoes it. */
    fun flipLike(postId: String) = everywhere(postId) {
        it.copy(likedByMe = !it.likedByMe, likeCount = (it.likeCount + if (it.likedByMe) -1 else 1).coerceAtLeast(0))
    }

    fun setCommentCount(postId: String, count: Int) = everywhere(postId) { it.copy(commentCount = count) }

    fun bumpComments(postId: String, delta: Int = 1) = everywhere(postId) { it.copy(commentCount = (it.commentCount + delta).coerceAtLeast(0)) }

    fun remove(postId: String) = pagers.values.forEach { it.remove(postId) }

    /** A post I just made goes to the top of Everyone and Following. */
    fun addMine(post: Post) {
        pagers.getValue(FeedScope.Everyone).prepend(post)
        pagers.getValue(FeedScope.Following).prepend(post)
    }

    fun clear() = pagers.values.forEach { it.clear() }

    private fun everywhere(postId: String, change: (Post) -> Post) = pagers.values.forEach { it.update(postId, change) }

    private fun ApiResult<FeedPage>.toPage(): ApiResult<PageOf<Post>> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(PageOf(value.items, value.nextCursor))
        is ApiResult.Err -> this
    }
}
