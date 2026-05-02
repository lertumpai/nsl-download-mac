import Foundation

struct DownloadProgress {
    enum Kind {
        case progress(percent: Double, speed: String, eta: String, downloaded: String, total: String)
        case muxing
        case error(String)
    }
    let kind: Kind
}

enum YTDLPError: LocalizedError {
    case unsupportedURL
    case loginRequired
    case networkError
    case diskFull
    case binaryNotFound
    case parseError(String)
    case processError(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedURL: return "This URL is not supported."
        case .loginRequired: return "This video requires login. Please import cookies."
        case .networkError: return "Download failed. Check your connection."
        case .diskFull: return "Not enough disk space."
        case .binaryNotFound: return "yt-dlp binary not found. Please install yt-dlp."
        case .parseError(let msg): return "Parse error: \(msg)"
        case .processError(let msg): return msg
        }
    }
}

final class YTDLPService {
    static let shared = YTDLPService()

    private var binaryPath: String {
        // Bundled at Contents/MacOS/bin/yt-dlp
        let bundled = Bundle.main.bundlePath + "/Contents/MacOS/bin/yt-dlp"
        if FileManager.default.fileExists(atPath: bundled) { return bundled }
        // Also try bundle Resources
        if let resource = Bundle.main.path(forResource: "yt-dlp", ofType: nil) { return resource }
        // Fall back to common system locations
        for path in ["/usr/local/bin/yt-dlp", "/opt/homebrew/bin/yt-dlp", "/usr/bin/yt-dlp"] {
            if FileManager.default.fileExists(atPath: path) { return path }
        }
        return "/usr/local/bin/yt-dlp"
    }

    func fetchMetadata(url: String) async throws -> VideoMetadata {
        let output = try await run(arguments: ["--dump-json", "--no-playlist", url])
        let firstLine = output.components(separatedBy: "\n").first { !$0.isEmpty } ?? output
        guard let data = firstLine.data(using: .utf8), !firstLine.isEmpty else {
            throw YTDLPError.parseError("No output from yt-dlp")
        }
        do {
            return try JSONDecoder().decode(VideoMetadata.self, from: data)
        } catch {
            if output.contains("Unsupported URL") || output.contains("is not a valid URL") {
                throw YTDLPError.unsupportedURL
            }
            if output.contains("requires login") || output.contains("Private video") || output.contains("members only") {
                throw YTDLPError.loginRequired
            }
            throw YTDLPError.parseError("Could not parse video info: \(error.localizedDescription)")
        }
    }

    func startDownload(
        url: String,
        formatSelector: String,
        outputTemplate: String,
        mergeFormat: String = "mp4",
        embedThumbnail: Bool = false,
        embedSubtitles: Bool = false,
        cookieBrowser: String? = nil
    ) throws -> (Process, AsyncStream<DownloadProgress>) {
        let path = binaryPath
        guard FileManager.default.fileExists(atPath: path) else {
            throw YTDLPError.binaryNotFound
        }

        var args = [
            "--format", formatSelector,
            "--merge-output-format", mergeFormat,
            "--output", outputTemplate,
            "--newline",
            "--no-playlist",
            "--progress"
        ]
        if embedThumbnail { args.append("--embed-thumbnail") }
        if embedSubtitles { args += ["--write-subs", "--embed-subs"] }
        if let browser = cookieBrowser, !browser.isEmpty {
            args += ["--cookies-from-browser", browser]
        }
        args.append(url)

        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        try process.run()

        let stream = AsyncStream<DownloadProgress> { continuation in
            Task {
                do {
                    for try await line in pipe.fileHandleForReading.bytes.lines {
                        if let p = Self.parseProgress(line: line) {
                            continuation.yield(p)
                        }
                    }
                } catch {
                    continuation.yield(DownloadProgress(kind: .error(error.localizedDescription)))
                }
                process.waitUntilExit()
                if process.terminationStatus != 0 {
                    continuation.yield(DownloadProgress(kind: .error("yt-dlp exited with code \(process.terminationStatus)")))
                }
                continuation.finish()
            }
        }

        return (process, stream)
    }

    func updateBinary() async throws {
        _ = try await run(arguments: ["-U"])
    }

    private func run(arguments: [String]) async throws -> String {
        let path = binaryPath
        guard FileManager.default.fileExists(atPath: path) else {
            throw YTDLPError.binaryNotFound
        }

        return try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: path)
            process.arguments = arguments
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            do {
                try process.run()
                process.waitUntilExit()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                let output = String(data: data, encoding: .utf8) ?? ""
                if process.terminationStatus != 0 && output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    continuation.resume(throwing: YTDLPError.processError("yt-dlp failed with exit code \(process.terminationStatus)"))
                } else {
                    continuation.resume(returning: output)
                }
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    // Parse lines like:
    // [download]  62.5% of  350.00MiB at   12.40MiB/s ETA 00:42
    // [ffmpeg] Merging formats into "output.mp4"
    static func parseProgress(line: String) -> DownloadProgress? {
        if line.contains("[ffmpeg]") || line.contains("[Merger]") || line.contains("Merging formats") {
            return DownloadProgress(kind: .muxing)
        }
        guard line.contains("[download]") && line.contains("%") else { return nil }

        func extract(pattern: String) -> String? {
            guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
            let range = NSRange(line.startIndex..., in: line)
            guard let match = re.firstMatch(in: line, range: range),
                  match.numberOfRanges > 1,
                  let r = Range(match.range(at: 1), in: line) else { return nil }
            return String(line[r])
        }

        guard let percentStr = extract(pattern: #"(\d+\.?\d*)%"#),
              let percent = Double(percentStr) else { return nil }

        let speed = extract(pattern: #"at\s+([\d.]+\s*\w+/s)"#) ?? ""
        let eta = extract(pattern: #"ETA\s+([\d:]+)"#) ?? ""
        let total = extract(pattern: #"of\s+~?([\d.]+\s*\w+)"#) ?? ""
        let downloaded = extract(pattern: #"([\d.]+\s*\w+)\s+of"#) ?? ""

        return DownloadProgress(kind: .progress(
            percent: percent / 100.0,
            speed: speed,
            eta: eta,
            downloaded: downloaded,
            total: total
        ))
    }

    static func buildFormatSelector(format: VideoFormat) -> String {
        if format.isAudioOnly { return "bestaudio[ext=m4a]/bestaudio" }
        if let height = format.height {
            return "bestvideo[height<=\(height)][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=\(height)]+bestaudio/best[height<=\(height)]"
        }
        return "bestvideo+bestaudio/best"
    }
}
