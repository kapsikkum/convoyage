import SwiftUI
import PhotosUI

struct FeedView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var showNew: Bool = false
    @State private var commentsFor: PostDto?
    @State private var reportTarget: PostDto?
    @State private var showReportDialog: Bool = false
    @State private var notice: String?

    var body: some View {
        let store: FeedStore = app.feed
        let scopeBinding: Binding<FeedScope> = Binding<FeedScope>(
            get: { return store.scope },
            set: { (newScope: FeedScope) in
                Task { await store.select(newScope) }
            }
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: "Community")
                        Text("Feed").font(CvFont.display(30))
                    }
                    Spacer()
                    Button {
                        showNew = true
                    } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(theme.signalInk)
                            .frame(width: 48, height: 48)
                            .cvPanel(fill: theme.signal, edge: theme.signal, cut: Cut.s)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("New post")
                }

                Segmented(items: FeedScope.allCases, title: { (s: FeedScope) -> String in return s.title }, selection: scopeBinding)

                if let n = notice {
                    Banner(title: n, kind: .go, icon: "checkmark.circle", actionTitle: "OK", action: { notice = nil })
                }
                if let e = store.error {
                    Banner(title: e, kind: .hazard, icon: "exclamationmark.triangle",
                           actionTitle: "Retry", action: { Task { await store.reload() } })
                }
                if store.items.isEmpty && store.loadedOnce && !store.loading {
                    Text(emptyText(store.scope))
                        .font(CvFont.text(15))
                        .foregroundStyle(theme.inkMuted)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, minHeight: 140)
                        .padding(.horizontal, 16)
                        .cvPanel(fill: theme.surface200, edge: theme.line)
                }
                LazyVStack(spacing: 14) {
                    ForEach(store.items) { (post: PostDto) in
                        PostCard(post: post,
                                 onLike: { Task { await store.toggleLike(postId: post.id) } },
                                 onComments: { commentsFor = post },
                                 onReport: {
                                     reportTarget = post
                                     showReportDialog = true
                                 },
                                 onDelete: {
                                     Task {
                                         if let err = await store.delete(postId: post.id) { notice = err }
                                     }
                                 })
                        .onAppear {
                            if post.id == store.items.last?.id {
                                Task { await store.loadMore() }
                            }
                        }
                    }
                }
                if store.loading && store.items.isEmpty {
                    HStack { Spacer(); ProgressView(); Spacer() }.frame(minHeight: 80)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 120)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .refreshable {
            await store.reload()
        }
        .background(theme.surface100.ignoresSafeArea())
        .task {
            if !store.loadedOnce { await store.reload() }
        }
        .sheet(isPresented: $showNew) {
            NewPostView()
        }
        .sheet(item: $commentsFor) { (post: PostDto) in
            CommentsView(post: post)
        }
        .confirmationDialog("Report this post", isPresented: $showReportDialog, titleVisibility: .visible) {
            ForEach(ReportReason.allCases, id: \.self) { (reason: ReportReason) in
                Button(reason.title) {
                    guard let target = reportTarget else { return }
                    Task {
                        let err: String? = await store.report(postId: target.id, reason: reason.rawValue)
                        notice = err ?? "Thanks, we will take a look."
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func emptyText(_ scope: FeedScope) -> String {
        switch scope {
        case .everyone: return "Nothing here yet. Be the first to post."
        case .following: return "Follow some drivers and their rides and builds show up here."
        case .nearby: return "No posts within 100 km of you yet."
        }
    }
}

enum ReportReason: String, CaseIterable, Hashable {
    case spam
    case abuse
    case nudity
    case violence
    case illegal
    case other

    var title: String {
        switch self {
        case .spam: return "Spam"
        case .abuse: return "Abuse or harassment"
        case .nudity: return "Nudity"
        case .violence: return "Violence"
        case .illegal: return "Illegal activity"
        case .other: return "Something else"
        }
    }
}

// MARK: - Card

struct PostCard: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    let post: PostDto
    let onLike: () -> Void
    let onComments: () -> Void
    let onReport: () -> Void
    let onDelete: () -> Void

    private var kindLabel: String {
        switch post.kind {
        case "ride": return "RIDE"
        case "build": return "BUILD"
        case "photo": return "PHOTO"
        default: return "POST"
        }
    }

    private var isMine: Bool {
        return post.author.id == app.me?.id
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                CvAvatar(photoURL: post.author.avatarPath, vehicle: post.author.vehicleBuild, initials: Formatters.initials(post.author.shownName), size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(post.author.shownName).font(CvFont.text(15, weight: .bold))
                    Text(Iso.ago(post.createdAt)).font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
                }
                Spacer()
                Text(kindLabel)
                    .font(CvFont.label(10))
                    .tracking(1.2)
                    .foregroundStyle(theme.signal)
                    .padding(.horizontal, 8)
                    .frame(minHeight: 24)
                    .cvPanel(fill: Color.clear, edge: theme.signal, cut: 4)
                Menu {
                    if isMine {
                        Button("Delete post", role: .destructive, action: onDelete)
                    } else {
                        Button("Report", role: .destructive, action: onReport)
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(theme.inkMuted)
                        .frame(width: 36, height: 36)
                }
            }

            if !post.body.isEmpty {
                Text(post.body).font(CvFont.text(15)).foregroundStyle(theme.ink)
            }

            switch post.kind {
            case "ride":
                if let ride = post.ride { rideBlock(ride) }
            case "build":
                if let v = post.vehicle { vehicleBlock(v.asVehicleDto) }
            default:
                EmptyView()
            }
            mediaBlock

            HStack(spacing: 22) {
                Button(action: onLike) {
                    HStack(spacing: 6) {
                        Image(systemName: post.likedByMe ? "heart.fill" : "heart")
                            .foregroundStyle(post.likedByMe ? theme.pulse : theme.inkMuted)
                        Text("\(post.likeCount)").font(CvFont.mono(15)).foregroundStyle(theme.ink)
                    }
                    .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Like")
                Button(action: onComments) {
                    HStack(spacing: 6) {
                        Image(systemName: "bubble.right").foregroundStyle(theme.inkMuted)
                        Text("\(post.commentCount)").font(CvFont.mono(15)).foregroundStyle(theme.ink)
                    }
                    .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Comments")
                Spacer()
            }
        }
        .padding(14)
        .cvPanel(fill: theme.surface200, edge: theme.line)
    }

    private func rideBlock(_ ride: GenPostRide) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if !ride.title.isEmpty {
                Text(ride.title).font(CvFont.display(20))
            }
            HStack {
                let d = Formatters.distance(ride.distanceM ?? 0)
                Readout(caption: "Distance", value: d.value, unit: d.unit, size: 24)
                Spacer()
                Readout(caption: "Time", value: Formatters.duration(Double(ride.durationS ?? 0)), size: 24)
                Spacer()
                Readout(caption: "Avg", value: "\(Formatters.kmh(ride.avgSpeedMps))", unit: "km/h", size: 24)
            }
        }
        .padding(12)
        .cvPanel(fill: theme.surface300, edge: theme.lineStrong, cut: Cut.s)
    }

    private func vehicleBlock(_ v: VehicleDto) -> some View {
        VehicleCard(vehicle: v, height: 120)
    }

    @ViewBuilder
    private var mediaBlock: some View {
        let media: [GenPostMediaItem] = post.media
        if media.count == 1, let m = media.first {
            RemoteImage(path: m.url)
                .frame(maxWidth: .infinity)
                .frame(height: 260)
                .clipShape(ChamferShape(cut: Cut.s))
        } else if media.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(media, id: \.id) { (m: GenPostMediaItem) in
                        RemoteImage(path: m.thumbUrl)
                            .frame(width: 200, height: 200)
                            .clipShape(ChamferShape(cut: Cut.s))
                    }
                }
            }
        }
    }
}

// MARK: - Comments

struct CommentsView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    let post: PostDto
    @State private var items: [CommentDto] = []
    @State private var nextCursor: String?
    @State private var text: String = ""
    @State private var loading: Bool = true
    @State private var sending: Bool = false
    @State private var errorText: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Comments").font(CvFont.display(22))
                Spacer()
                Button("Done") { dismiss() }
                    .font(CvFont.text(15, weight: .bold))
                    .foregroundStyle(theme.signal)
            }
            .padding(16)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if loading {
                        HStack { Spacer(); ProgressView(); Spacer() }.frame(minHeight: 80)
                    } else if items.isEmpty {
                        Text("No comments yet.").font(CvFont.text(14)).foregroundStyle(theme.inkMuted).padding(.vertical, 24)
                    }
                    ForEach(items) { (c: CommentDto) in
                        HStack(alignment: .top, spacing: 10) {
                            CvAvatar(photoURL: c.author.avatarPath, vehicle: c.author.vehicleBuild, initials: Formatters.initials(c.author.shownName), size: 32)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(c.author.shownName).font(CvFont.text(13, weight: .bold))
                                    Text(Iso.ago(c.createdAt)).font(CvFont.text(11)).foregroundStyle(theme.inkMuted)
                                }
                                Text(c.body).font(CvFont.text(14))
                            }
                            Spacer()
                            if c.author.id == app.me?.id || post.author.id == app.me?.id {
                                Button {
                                    remove(c)
                                } label: {
                                    Image(systemName: "trash").foregroundStyle(theme.inkMuted).frame(width: 36, height: 36)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Delete comment")
                            }
                        }
                    }
                    if nextCursor != nil {
                        CvButton(title: "Load more", kind: .ghost) {
                            Task { await load(more: true) }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }

            if let e = errorText {
                Text(e).font(CvFont.text(12)).foregroundStyle(theme.pulse).padding(.horizontal, 16)
            }
            HStack(spacing: 8) {
                CvField(placeholder: "Add a comment", text: $text, autocap: .sentences)
                Button {
                    send()
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(theme.signalInk)
                        .frame(width: 52, height: 52)
                        .cvPanel(fill: theme.signal, edge: theme.signal, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .opacity(sending || text.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
                .disabled(sending || text.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityLabel("Send comment")
            }
            .padding(16)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .task {
            await load(more: false)
        }
    }

    private func load(more: Bool) async {
        if !more { loading = true }
        let r: ApiResult<CommentPageDto> = await app.api.comments(postId: post.id, before: more ? nextCursor : nil)
        loading = false
        switch r {
        case .ok(let page):
            if more { items.append(contentsOf: page.items) } else { items = page.items }
            nextCursor = page.nextCursor
            errorText = nil
        case .failure(let f):
            errorText = f.message
        }
    }

    private func send() {
        let body: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.isEmpty { return }
        sending = true
        Task {
            let r: ApiResult<CommentDto> = await app.api.addComment(postId: post.id, body: body)
            sending = false
            switch r {
            case .ok(let c):
                items.append(c)
                text = ""
                errorText = nil
                app.feed.bumpComments(postId: post.id, delta: 1)
            case .failure(let f):
                errorText = f.message
            }
        }
    }

    private func remove(_ c: CommentDto) {
        Task {
            let r: ApiResult<Empty> = await app.api.deleteComment(id: c.id)
            switch r {
            case .ok:
                items.removeAll(where: { (x: CommentDto) -> Bool in return x.id == c.id })
                app.feed.bumpComments(postId: post.id, delta: -1)
            case .failure(let f):
                errorText = f.message
            }
        }
    }
}

// MARK: - New post

enum NewPostKind: String, CaseIterable, Hashable {
    case text
    case photo
    case build

    var title: String {
        switch self {
        case .text: return "Text"
        case .photo: return "Photo"
        case .build: return "Build"
        }
    }
}

struct NewPostView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var kind: NewPostKind = .text
    @State private var bodyText: String = ""
    @State private var picks: [PhotosPickerItem] = []
    @State private var images: [UIImage] = []
    @State private var vehicleId: String?
    @State private var visibility: String = "followers"
    @State private var posting: Bool = false
    @State private var errorText: String?

    private var canPost: Bool {
        if posting { return false }
        switch kind {
        case .text: return !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .photo: return !images.isEmpty
        case .build: return vehicleId != nil
        }
    }

    var body: some View {
        let kindBinding: Binding<NewPostKind> = Binding<NewPostKind>(
            get: { return kind },
            set: { (k: NewPostKind) in kind = k }
        )
        let visBinding: Binding<String> = Binding<String>(
            get: { return visibility },
            set: { (v: String) in visibility = v }
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("New post").font(CvFont.display(26))
                    Spacer()
                    Button("Cancel") { dismiss() }
                        .font(CvFont.text(15, weight: .bold))
                        .foregroundStyle(theme.inkMuted)
                }
                Segmented(items: NewPostKind.allCases, title: { (k: NewPostKind) -> String in return k.title }, selection: kindBinding)

                ZStack(alignment: .topLeading) {
                    TextEditor(text: $bodyText)
                        .font(CvFont.text(16))
                        .scrollContentBackground(.hidden)
                        .padding(8)
                        .frame(minHeight: 120)
                    if bodyText.isEmpty {
                        Text(kind == .text ? "What is on your mind?" : "Say something about it (optional)")
                            .font(CvFont.text(16))
                            .foregroundStyle(theme.inkMuted)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                    }
                }
                .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.m)

                if kind == .photo {
                    photoSection
                }
                if kind == .build {
                    buildSection
                }

                SectionLabel(text: "Who can see it")
                Segmented(items: ["followers", "public"], title: { (v: String) -> String in
                    return v == "public" ? "Public" : "Followers"
                }, selection: visBinding)

                if let e = errorText {
                    Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                }
                CvButton(title: posting ? "Posting..." : "Post") {
                    post()
                }
                .opacity(canPost ? 1 : 0.5)
                .disabled(!canPost)
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onChange(of: picks) { (_: [PhotosPickerItem], newValue: [PhotosPickerItem]) in
            Task { await loadPicks(newValue) }
        }
        .task {
            if !app.garage.loaded { await app.garage.load() }
        }
    }

    @ViewBuilder
    private var photoSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            PhotosPicker(selection: $picks, maxSelectionCount: 4, matching: .images) {
                HStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle")
                    Text("CHOOSE PHOTOS").tracking(1.2)
                }
                .font(CvFont.text(14, weight: .bold))
                .foregroundStyle(theme.ink)
                .frame(maxWidth: .infinity, minHeight: 48)
                .cvPanel(fill: Color.clear, edge: theme.lineStrong, cut: Cut.m)
            }
            if !images.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(0..<images.count, id: \.self) { (i: Int) in
                            Image(uiImage: images[i])
                                .resizable()
                                .scaledToFill()
                                .frame(width: 110, height: 110)
                                .clipShape(ChamferShape(cut: Cut.s))
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var buildSection: some View {
        let list: [VehicleDto] = app.garage.vehicles.filter { (v: VehicleDto) -> Bool in return v.soldAt == nil }
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Which build")
            if list.isEmpty {
                Text("Add a vehicle in the Garage tab first.").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
            }
            ForEach(list) { (v: VehicleDto) in
                Button {
                    vehicleId = v.id
                } label: {
                    HStack(spacing: 12) {
                        Circle().fill(Color(hexString: v.build?.paint ?? "#9aa3ad")).frame(width: 24, height: 24)
                        Text(v.title).font(CvFont.text(15, weight: .bold))
                        Spacer()
                        if vehicleId == v.id {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(theme.signal)
                        }
                    }
                    .padding(.horizontal, 12)
                    .frame(minHeight: 52)
                    .cvPanel(fill: theme.surface200, edge: vehicleId == v.id ? theme.signal : theme.line, cut: Cut.s)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func loadPicks(_ items: [PhotosPickerItem]) async {
        var loaded: [UIImage] = []
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                loaded.append(img)
            }
        }
        images = loaded
    }

    private func post() {
        posting = true
        errorText = nil
        Task {
            var body: [String: Any] = ["kind": kind.rawValue, "visibility": visibility]
            let text: String = bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { body["body"] = text }
            if kind == .photo {
                var ids: [String] = []
                for img in images {
                    guard let data = ImagePrep.jpeg(img) else { continue }
                    let up: ApiResult<MediaDto> = await app.api.uploadImage(data)
                    switch up {
                    case .ok(let m):
                        ids.append(m.id)
                    case .failure(let f):
                        errorText = f.message
                        posting = false
                        return
                    }
                }
                body["mediaIds"] = ids
            }
            if kind == .build, let vid = vehicleId {
                body["vehicleId"] = vid
            }
            let r: ApiResult<PostDto> = await app.api.createPost(body)
            posting = false
            switch r {
            case .ok(let p):
                app.feed.prepend(p)
                dismiss()
            case .failure(let f):
                errorText = f.message
            }
        }
    }
}
