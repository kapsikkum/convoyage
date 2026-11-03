import Foundation
import UIKit
import CoreLocation
import Observation

/// The shutter flow: camera, a confirm sheet with the per-photo "Include location" toggle, upload through the media
/// API, then attach to the current ride (or post to the feed when no ride is recording).
@Observable
@MainActor
final class PhotoCapture {
    var showCamera: Bool = false
    var image: UIImage?
    var includeLocation: Bool = true
    var postVisibility: String = "followers"
    var busy: Bool = false
    var message: String?

    @ObservationIgnored private var capturedAt: Date = Date()
    @ObservationIgnored private var capturedCoordinate: CLLocationCoordinate2D?
    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let recorder: RideRecorder
    @ObservationIgnored private let location: LocationService
    @ObservationIgnored private let feed: FeedStore

    init(api: ApiClient, recorder: RideRecorder, location: LocationService, feed: FeedStore) {
        self.api = api
        self.recorder = recorder
        self.location = location
        self.feed = feed
    }

    var hasRide: Bool { return recorder.isActive }

    var cameraAvailable: Bool {
        return UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    /// The shutter button.
    func begin() {
        message = nil
        if cameraAvailable {
            showCamera = true
        } else {
            message = "No camera on this device."
        }
    }

    func didCapture(_ img: UIImage) {
        capturedAt = Date()
        capturedCoordinate = location.latest?.coordinate
        includeLocation = capturedCoordinate != nil
        showCamera = false
        // Let the camera cover finish dismissing before the confirm sheet comes up.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            self?.image = img
        }
    }

    func cancel() {
        image = nil
        showCamera = false
    }

    var hasLocation: Bool { return capturedCoordinate != nil }

    func submit() async {
        guard let img = image, !busy else { return }
        guard let data = ImagePrep.jpeg(img) else {
            message = "Could not prepare the photo."
            return
        }
        busy = true
        let uploaded: ApiResult<MediaDto> = await api.uploadImage(data)
        switch uploaded {
        case .failure(let f):
            busy = false
            message = f.message
        case .ok(let media):
            var lat: Double? = nil
            var lng: Double? = nil
            if includeLocation, let c = capturedCoordinate {
                lat = c.latitude
                lng = c.longitude
            }
            if recorder.isActive {
                await recorder.addPhoto(mediaId: media.id, lat: lat, lng: lng, takenAt: capturedAt)
                message = "Photo added to your ride."
                image = nil
            } else {
                var body: [String: Any] = ["kind": "photo", "mediaIds": [media.id], "visibility": postVisibility]
                if let la = lat, let ln = lng {
                    body["lat"] = la
                    body["lng"] = ln
                }
                let posted: ApiResult<PostDto> = await api.createPost(body)
                switch posted {
                case .ok(let post):
                    feed.prepend(post)
                    message = "Posted to your feed."
                    image = nil
                case .failure(let f):
                    message = "Uploaded, but the post failed: \(f.message)"
                }
            }
            busy = false
        }
    }
}
