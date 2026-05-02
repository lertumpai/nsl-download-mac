import Foundation
import AppKit

@MainActor
final class DownloadManager: ObservableObject {
    static let shared = DownloadManager()

    @Published var items: [DownloadItem] = []
    @Published var pendingFormatItem: DownloadItem?

    private let settings = AppSettings.shared
    private var activeTasks: [UUID: Task<Void, Never>] = [:]
    private var clipboardTimer: Timer?

    var queueItems: [DownloadItem] {
        items.filter {
            switch $0.status {
            case .queued, .fetchingMetadata, .awaitingFormat, .downloading, .muxing, .paused: return true
            default: return false
            }
        }
    }

    var completedItems: [DownloadItem] {
        items.filter {
            switch $0.status {
            case .completed, .failed, .cancelled: return true
            default: return false
            }
        }
    }

    var activeDownloadCount: Int {
        items.filter { $0.status.isActive }.count
    }

    func addURL(_ urlString: String) {
        let urls = urlString.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && (URL(string: $0)?.scheme?.hasPrefix("http") == true || $0.hasPrefix("rtmp")) }

        for url in urls {
            let item = DownloadItem(url: url)
            items.insert(item, at: 0)
            fetchMetadata(for: item)
        }
    }

    func fetchMetadata(for item: DownloadItem) {
        item.status = .fetchingMetadata
        let task = Task {
            do {
                let metadata = try await YTDLPService.shared.fetchMetadata(url: item.url)
                item.cachedMetadata = metadata
                item.status = .awaitingFormat(metadata)
                if pendingFormatItem == nil {
                    pendingFormatItem = item
                }
            } catch {
                item.status = .failed(error.localizedDescription)
            }
        }
        activeTasks[item.id] = task
    }

    func startDownload(item: DownloadItem, format: VideoFormat, metadata: VideoMetadata) {
        pendingFormatItem = nil

        // Queue further items if at capacity
        if activeDownloadCount >= settings.maxConcurrentDownloads {
            item.status = .queued
            return
        }

        item.status = .downloading(progress: 0, speed: "", eta: "", downloaded: "", total: "")

        let task = Task {
            do {
                let outputDir = settings.saveFolderURL
                try FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)

                let template = outputDir.appendingPathComponent(settings.filenameTemplate).path
                let formatSelector = YTDLPService.buildFormatSelector(format: format)
                let cookieBrowser = settings.cookieBrowser.isEmpty ? nil : settings.cookieBrowser

                let (process, stream) = try YTDLPService.shared.startDownload(
                    url: item.url,
                    formatSelector: formatSelector,
                    outputTemplate: template,
                    mergeFormat: settings.defaultFormat,
                    embedThumbnail: settings.embedThumbnail,
                    embedSubtitles: settings.embedSubtitles,
                    cookieBrowser: cookieBrowser
                )
                item.process = process

                for await progress in stream {
                    switch progress.kind {
                    case .progress(let pct, let speed, let eta, let downloaded, let total):
                        item.status = .downloading(progress: pct, speed: speed, eta: eta, downloaded: downloaded, total: total)
                    case .muxing:
                        item.status = .muxing
                    case .error(let err):
                        let userMessage = classifyError(err)
                        item.status = .failed(userMessage)
                        NotificationService.shared.notifyFailure(title: metadata.title, error: userMessage)
                        processQueue()
                        return
                    }
                }

                // Process exited — find the output file
                let fileURL = findOutputFile(in: outputDir, title: metadata.title)
                    ?? outputDir
                item.status = .completed(fileURL)
                NotificationService.shared.notifyCompletion(title: metadata.title)
                processQueue()

            } catch {
                item.status = .failed(error.localizedDescription)
                processQueue()
            }
        }
        activeTasks[item.id] = task
    }

    func pause(_ item: DownloadItem) {
        item.process?.suspend()
        item.status = .paused
    }

    func resume(_ item: DownloadItem) {
        if let process = item.process, process.isRunning {
            process.resume()
            item.status = .downloading(progress: item.status.progress, speed: "", eta: "", downloaded: "", total: "")
        } else {
            // Process died; retry from metadata
            item.status = .queued
            fetchMetadata(for: item)
        }
    }

    func cancel(_ item: DownloadItem) {
        activeTasks[item.id]?.cancel()
        activeTasks.removeValue(forKey: item.id)
        item.process?.terminate()
        item.process = nil
        item.status = .cancelled
    }

    func retry(_ item: DownloadItem) {
        activeTasks[item.id]?.cancel()
        activeTasks.removeValue(forKey: item.id)
        item.process?.terminate()
        item.process = nil
        fetchMetadata(for: item)
    }

    func remove(_ item: DownloadItem) {
        cancel(item)
        items.removeAll { $0.id == item.id }
        if pendingFormatItem?.id == item.id {
            pendingFormatItem = nil
            showNextPendingFormat()
        }
    }

    func clearCompleted() {
        items.removeAll {
            switch $0.status {
            case .completed, .failed, .cancelled: return true
            default: return false
            }
        }
    }

    func setupClipboardMonitoring() {
        guard settings.clipboardMonitoring else { stopClipboardMonitoring(); return }
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkClipboard() }
        }
    }

    func stopClipboardMonitoring() {
        clipboardTimer?.invalidate()
        clipboardTimer = nil
    }

    private func processQueue() {
        let available = settings.maxConcurrentDownloads - activeDownloadCount
        guard available > 0 else { return }

        let waiting = items.filter { if case .queued = $0.status { return true }; return false }
        for item in waiting.prefix(available) {
            fetchMetadata(for: item)
        }

        // Show next awaiting-format item
        showNextPendingFormat()
    }

    private func showNextPendingFormat() {
        guard pendingFormatItem == nil else { return }
        pendingFormatItem = items.first {
            if case .awaitingFormat = $0.status { return true }
            return false
        }
    }

    private func findOutputFile(in directory: URL, title: String) -> URL? {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.creationDateKey]
        ) else { return nil }

        let sanitized = String(title.prefix(50))
        let exts = Set(["mp4", "mkv", "webm", "m4a", "opus"])
        return files
            .filter { exts.contains($0.pathExtension.lowercased()) }
            .filter { $0.lastPathComponent.contains(sanitized) || sanitized.isEmpty }
            .sorted {
                let d1 = (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                let d2 = (try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                return d1 > d2
            }
            .first
    }

    private func classifyError(_ output: String) -> String {
        if output.contains("No space left") || output.contains("disk full") { return "Not enough disk space." }
        if output.contains("requires login") || output.contains("Private video") { return "This video requires login. Please import cookies." }
        if output.contains("Unsupported URL") { return "This URL is not supported." }
        if output.contains("Unable to connect") || output.contains("timed out") { return "Download failed. Check your connection." }
        if output.contains("Merge failed") || output.contains("ffmpeg") && output.contains("error") { return "Could not merge audio/video." }
        return output.isEmpty ? "Download failed." : output
    }

    private var lastClipboardString = ""
    private func checkClipboard() {
        let str = NSPasteboard.general.string(forType: .string) ?? ""
        guard str != lastClipboardString,
              let url = URL(string: str),
              url.scheme?.hasPrefix("http") == true else { return }
        lastClipboardString = str
        addURL(str)
    }
}
