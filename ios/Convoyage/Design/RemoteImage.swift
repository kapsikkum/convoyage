import SwiftUI
import UIKit

/// In-memory cache of decoded server images (media are private, so AsyncImage cannot be used).
@MainActor
final class ImageCache {
    static let shared: ImageCache = ImageCache()
    private let cache: NSCache<NSString, UIImage> = NSCache<NSString, UIImage>()

    func image(for key: String) -> UIImage? {
        return cache.object(forKey: key as NSString)
    }

    func store(_ image: UIImage, for key: String) {
        cache.setObject(image, forKey: key as NSString)
    }
}

/// An image from the Convoyage media API, fetched with the session's bearer token.
struct RemoteImage: View {
    /// Server-relative path such as `/v1/media/<id>/thumb`.
    let path: String
    var fill: Bool = true
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            theme.surface300
            if let image = image {
                if fill {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(uiImage: image).resizable().scaledToFit()
                }
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 22))
                    .foregroundStyle(theme.inkMuted)
            }
        }
        .clipped()
        .task(id: path) {
            await load()
        }
    }

    private func load() async {
        let key: String = app.serverURL + path
        if let hit = ImageCache.shared.image(for: key) {
            image = hit
            return
        }
        if let data = await app.api.fetchBytes(path: path), let img = UIImage(data: data) {
            ImageCache.shared.store(img, for: key)
            image = img
        }
    }
}
