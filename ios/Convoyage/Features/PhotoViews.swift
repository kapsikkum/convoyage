import SwiftUI
import UIKit

/// UIImagePickerController with the camera source.
struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        return Coordinator(onCapture: onCapture, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker: UIImagePickerController = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onCapture: (UIImage) -> Void
        let onCancel: () -> Void

        init(onCapture: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onCapture = onCapture
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage {
                onCapture(img)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}

/// Confirm sheet after the shutter: preview, per-photo "Include location" toggle, attach or post.
struct PhotoConfirmView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let photos: PhotoCapture = app.photos
        let includeBinding: Binding<Bool> = Binding<Bool>(
            get: { return photos.includeLocation },
            set: { (v: Bool) in photos.includeLocation = v }
        )
        let visBinding: Binding<String> = Binding<String>(
            get: { return photos.postVisibility },
            set: { (v: String) in photos.postVisibility = v }
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Photo").font(CvFont.display(26))
                if let img = photos.image {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 340)
                        .clipShape(ChamferShape(cut: Cut.m))
                }
                Toggle(isOn: includeBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Include location").font(CvFont.text(15, weight: .bold))
                        Text(photos.hasLocation ? "Pins the photo on the map." : "No location fix yet.")
                            .font(CvFont.text(12))
                            .foregroundStyle(theme.inkMuted)
                    }
                }
                .tint(theme.signal)
                .disabled(!photos.hasLocation)

                if photos.hasRide {
                    Banner(title: "Added to your current ride", kind: .info, icon: "figure.outdoor.cycle")
                } else {
                    Banner(title: "No ride is recording", subtitle: "This photo will be posted to your feed.", kind: .info, icon: "photo")
                    Segmented(items: ["followers", "public"], title: { (v: String) -> String in
                        return v == "public" ? "Public" : "Followers"
                    }, selection: visBinding)
                }
                if let m = photos.message {
                    Banner(title: m, kind: .hazard, icon: "exclamationmark.triangle")
                }
                CvButton(title: photos.busy ? "Uploading..." : (photos.hasRide ? "Attach to ride" : "Post to feed")) {
                    Task { await photos.submit() }
                }
                .opacity(photos.busy ? 0.5 : 1)
                .disabled(photos.busy)
                CvButton(title: "Discard", kind: .ghost) { photos.cancel() }
                    .disabled(photos.busy)
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .interactiveDismissDisabled(photos.busy)
    }
}

/// Attaches the camera cover and the confirm sheet to a view. Both the convoy screen and the ride HUD use it.
struct PhotoCaptureHost: ViewModifier {
    @Environment(AppState.self) private var app

    func body(content: Content) -> some View {
        let photos: PhotoCapture = app.photos
        let cameraBinding: Binding<Bool> = Binding<Bool>(
            get: { return photos.showCamera },
            set: { (v: Bool) in photos.showCamera = v }
        )
        let confirmBinding: Binding<Bool> = Binding<Bool>(
            get: { return photos.image != nil },
            set: { (v: Bool) in
                if !v { photos.cancel() }
            }
        )
        content
            .fullScreenCover(isPresented: cameraBinding) {
                CameraPicker(onCapture: { (img: UIImage) in photos.didCapture(img) },
                             onCancel: { photos.cancel() })
                    .ignoresSafeArea()
            }
            .sheet(isPresented: confirmBinding) {
                PhotoConfirmView()
            }
    }
}

extension View {
    func photoCapture() -> some View {
        return modifier(PhotoCaptureHost())
    }
}
