import Foundation

enum DownloadStatus {
    case queued
    case fetchingMetadata
    case awaitingFormat(VideoMetadata)
    case downloading(progress: Double, speed: String, eta: String, downloaded: String, total: String)
    case muxing
    case completed(URL)
    case failed(String)
    case paused
    case cancelled

    var isActive: Bool {
        switch self {
        case .fetchingMetadata, .downloading, .muxing: return true
        default: return false
        }
    }

    var progress: Double {
        switch self {
        case .downloading(let p, _, _, _, _): return p
        case .muxing: return 1.0
        default: return 0
        }
    }
}

@MainActor
final class DownloadItem: ObservableObject, Identifiable {
    let id = UUID()
    let url: String

    @Published var status: DownloadStatus = .queued
    var process: Process?

    var title: String {
        if case .awaitingFormat(let meta) = status { return meta.title }
        if case .downloading = status, let meta = cachedMetadata { return meta.title }
        if case .muxing = status, let meta = cachedMetadata { return meta.title }
        if case .completed = status, let meta = cachedMetadata { return meta.title }
        if case .failed = status, let meta = cachedMetadata { return meta.title }
        return url
    }

    var cachedMetadata: VideoMetadata?

    init(url: String) {
        self.url = url
    }
}
