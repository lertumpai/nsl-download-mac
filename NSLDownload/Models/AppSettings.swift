import Foundation

final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    private let defaults = UserDefaults.standard

    @Published var defaultQuality: String { didSet { defaults.set(defaultQuality, forKey: "defaultQuality") } }
    @Published var defaultFormat: String { didSet { defaults.set(defaultFormat, forKey: "defaultFormat") } }
    @Published var saveFolder: String { didSet { defaults.set(saveFolder, forKey: "saveFolder") } }
    @Published var filenameTemplate: String { didSet { defaults.set(filenameTemplate, forKey: "filenameTemplate") } }
    @Published var maxConcurrentDownloads: Int { didSet { defaults.set(maxConcurrentDownloads, forKey: "maxConcurrentDownloads") } }
    @Published var embedThumbnail: Bool { didSet { defaults.set(embedThumbnail, forKey: "embedThumbnail") } }
    @Published var embedSubtitles: Bool { didSet { defaults.set(embedSubtitles, forKey: "embedSubtitles") } }
    @Published var clipboardMonitoring: Bool { didSet { defaults.set(clipboardMonitoring, forKey: "clipboardMonitoring") } }
    @Published var menuBarIcon: Bool { didSet { defaults.set(menuBarIcon, forKey: "menuBarIcon") } }
    @Published var cookieBrowser: String { didSet { defaults.set(cookieBrowser, forKey: "cookieBrowser") } }

    var saveFolderURL: URL {
        if !saveFolder.isEmpty { return URL(fileURLWithPath: saveFolder) }
        let movies = FileManager.default.urls(for: .moviesDirectory, in: .userDomainMask).first!
        return movies.appendingPathComponent("NSL Downloads")
    }

    private init() {
        defaultQuality = defaults.string(forKey: "defaultQuality") ?? "1080p"
        defaultFormat = defaults.string(forKey: "defaultFormat") ?? "mp4"
        saveFolder = defaults.string(forKey: "saveFolder") ?? ""
        filenameTemplate = defaults.string(forKey: "filenameTemplate") ?? "%(title)s [%(height)sp].%(ext)s"
        let concurrency = defaults.integer(forKey: "maxConcurrentDownloads")
        maxConcurrentDownloads = concurrency > 0 ? concurrency : 3
        embedThumbnail = defaults.bool(forKey: "embedThumbnail")
        embedSubtitles = defaults.bool(forKey: "embedSubtitles")
        clipboardMonitoring = defaults.bool(forKey: "clipboardMonitoring")
        menuBarIcon = defaults.object(forKey: "menuBarIcon") as? Bool ?? true
        cookieBrowser = defaults.string(forKey: "cookieBrowser") ?? ""
    }
}
