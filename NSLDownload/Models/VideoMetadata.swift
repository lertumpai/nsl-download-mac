import Foundation

struct VideoMetadata: Decodable {
    let id: String
    let title: String
    let thumbnail: String?
    let duration: Double?
    let webpageUrl: String
    let extractor: String
    let formats: [VideoFormat]

    enum CodingKeys: String, CodingKey {
        case id, title, thumbnail, duration, extractor, formats
        case webpageUrl = "webpage_url"
    }

    var displayFormats: [VideoFormat] {
        var byHeight: [Int: VideoFormat] = [:]
        var audioOnly: VideoFormat?

        for fmt in formats {
            guard fmt.ext != "mhtml", fmt.ext != "none" else { continue }
            if fmt.isAudioOnly {
                if let existing = audioOnly {
                    if (fmt.tbr ?? 0) > (existing.tbr ?? 0) { audioOnly = fmt }
                } else {
                    audioOnly = fmt
                }
                continue
            }
            guard let h = fmt.height else { continue }
            if let existing = byHeight[h] {
                if (fmt.tbr ?? 0) > (existing.tbr ?? 0) { byHeight[h] = fmt }
            } else {
                byHeight[h] = fmt
            }
        }

        var result = byHeight.values.sorted { ($0.height ?? 0) > ($1.height ?? 0) }
        if let audio = audioOnly { result.append(audio) }
        return result
    }

    var defaultFormat: VideoFormat? {
        displayFormats.first { ($0.height ?? 0) <= 1080 && !$0.isAudioOnly }
            ?? displayFormats.first
    }
}

struct VideoFormat: Identifiable, Decodable {
    let id: String
    let formatNote: String?
    let ext: String
    let height: Int?
    let width: Int?
    let vcodec: String?
    let acodec: String?
    let filesize: Int64?
    let filesizeApprox: Int64?
    let tbr: Double?
    let fps: Double?

    enum CodingKeys: String, CodingKey {
        case ext, height, width, vcodec, acodec, filesize, fps, tbr
        case id = "format_id"
        case formatNote = "format_note"
        case filesizeApprox = "filesize_approx"
    }

    var isAudioOnly: Bool {
        vcodec == "none" || (vcodec == nil && acodec != nil && acodec != "none")
    }

    var isVideoOnly: Bool {
        acodec == "none" || (acodec == nil && vcodec != nil && vcodec != "none")
    }

    var effectiveFilesize: Int64? { filesize ?? filesizeApprox }

    var displayResolution: String {
        if let h = height {
            if h >= 2160 { return "4K" }
            if h >= 1440 { return "1440p" }
            if h >= 1080 { return "1080p" }
            if h >= 720 { return "720p" }
            if h >= 480 { return "480p" }
            if h >= 360 { return "360p" }
            return "\(h)p"
        }
        if isAudioOnly { return "Audio Only" }
        return "Unknown"
    }

    var displayCodec: String {
        var parts: [String] = []
        if let v = vcodec, v != "none" {
            parts.append(v.components(separatedBy: ".").first ?? v)
        }
        if let a = acodec, a != "none" {
            let short = a.components(separatedBy: ".").first ?? a
            if !parts.contains(short) { parts.append(short) }
        }
        return parts.joined(separator: " + ")
    }

    var displaySize: String {
        guard let size = effectiveFilesize else { return "" }
        let mb = Double(size) / 1_048_576
        if mb >= 1024 { return String(format: "~%.1f GB", mb / 1024) }
        return String(format: "~%.0f MB", mb)
    }
}
