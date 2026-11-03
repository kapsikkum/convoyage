import Foundation
import UIKit
import CoreLocation
import Observation

enum FeedScope: String, CaseIterable, Hashable {
    case everyone
    case following
    case nearby

    var title: String {
        switch self {
        case .everyone: return "Everyone"
        case .following: return "Following"
        case .nearby: return "Nearby"
        }
    }
}

/// The feed (`/v1/feed`): scopes, keyset paging, refresh, likes and posting.
@Observable
@MainActor
final class FeedStore {
    var scope: FeedScope = .everyone
    var items: [PostDto] = []
    var loading: Bool = false
    var error: String?
    var nextCursor: String?
    var loadedOnce: Bool = false

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let location: LocationService
    @ObservationIgnored private var generation: Int = 0

    init(api: ApiClient, location: LocationService) {
        self.api = api
        self.location = location
    }

    func select(_ s: FeedScope) async {
        scope = s
        items = []
        nextCursor = nil
        loadedOnce = false
        await reload()
    }

    /// Fresh first page (pull to refresh).
    func reload() async {
        generation += 1
        let gen: Int = generation
        loading = true
        error = nil
        let loc: CLLocation? = location.latest
        if scope == .nearby && loc == nil {
            loading = false
            error = "Waiting for a location fix. Check that location is on."
            return
        }
        let r: ApiResult<FeedPageDto> = await api.feed(scope: scope.rawValue, before: nil,
                                                         lat: loc?.coordinate.latitude, lng: loc?.coordinate.longitude)
        guard gen == generation else { return }
        loading = false
        switch r {
        case .ok(let page):
            items = page.items
            nextCursor = page.nextCursor
            loadedOnce = true
        case .failure(let f):
            if !f.isUnauthorized { error = f.message }
        }
    }

    /// Next page, when the last card appears.
    func loadMore() async {
        guard !loading, let cursor = nextCursor else { return }
        let gen: Int = generation
        loading = true
        let loc: CLLocation? = location.latest
        let r: ApiResult<FeedPageDto> = await api.feed(scope: scope.rawValue, before: cursor,
                                                         lat: loc?.coordinate.latitude, lng: loc?.coordinate.longitude)
        guard gen == generation else { return }
        loading = false
        switch r {
        case .ok(let page):
            let known: Set<String> = Set(items.map { (p: PostDto) -> String in return p.id })
            items.append(contentsOf: page.items.filter { (p: PostDto) -> Bool in return !known.contains(p.id) })
            nextCursor = page.nextCursor
        case .failure(let f):
            if !f.isUnauthorized { error = f.message }
        }
    }

    func clear() {
        generation += 1
        items = []
        nextCursor = nil
        loadedOnce = false
        loading = false
        error = nil
    }

    func prepend(_ post: PostDto) {
        items.insert(post, at: 0)
    }

    func remove(id: String) {
        items.removeAll(where: { (p: PostDto) -> Bool in return p.id == id })
    }

    func bumpComments(postId: String, delta: Int) {
        guard let idx = items.firstIndex(where: { (p: PostDto) -> Bool in return p.id == postId }) else { return }
        items[idx].commentCount = max(0, items[idx].commentCount + delta)
    }

    /// Optimistic like; reverts on failure.
    func toggleLike(postId: String) async {
        guard let idx = items.firstIndex(where: { (p: PostDto) -> Bool in return p.id == postId }) else { return }
        let was: Bool = items[idx].likedByMe
        let wasCount: Int = items[idx].likeCount
        items[idx].likedByMe = !was
        items[idx].likeCount = max(0, wasCount + (was ? -1 : 1))
        let r: ApiResult<LikeStateDto> = await api.like(postId: postId, on: !was)
        guard let again = items.firstIndex(where: { (p: PostDto) -> Bool in return p.id == postId }) else { return }
        switch r {
        case .ok(let s):
            items[again].likedByMe = s.likedByMe
            items[again].likeCount = s.likeCount
        case .failure:
            items[again].likedByMe = was
            items[again].likeCount = wasCount
        }
    }

    func report(postId: String, reason: String) async -> String? {
        let r: ApiResult<Empty> = await api.report(targetType: "post", targetId: postId, reason: reason, detail: nil)
        switch r {
        case .ok: return nil
        case .failure(let f): return f.message
        }
    }

    func delete(postId: String) async -> String? {
        let r: ApiResult<Empty> = await api.deletePost(id: postId)
        switch r {
        case .ok:
            remove(id: postId)
            return nil
        case .failure(let f):
            return f.message
        }
    }
}

/// Downscaling and JPEG encoding for uploads (the server takes JPEG, PNG or WebP up to 15 MB, never HEIC).
enum ImagePrep {
    static func jpeg(_ image: UIImage, maxEdge: CGFloat = 2048) -> Data? {
        let size: CGSize = image.size
        let longest: CGFloat = max(size.width, size.height)
        if longest <= 0 { return nil }
        let scale: CGFloat = min(1, maxEdge / longest)
        let target: CGSize = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format: UIGraphicsImageRendererFormat = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer: UIGraphicsImageRenderer = UIGraphicsImageRenderer(size: target, format: format)
        let flat: UIImage = renderer.image { (_: UIGraphicsImageRendererContext) in
            image.draw(in: CGRect(origin: CGPoint.zero, size: target))
        }
        return flat.jpegData(compressionQuality: 0.85)
    }
}
