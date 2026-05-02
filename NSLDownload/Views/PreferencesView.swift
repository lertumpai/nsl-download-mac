import SwiftUI
import AppKit

struct PreferencesView: View {
    @EnvironmentObject var settings: AppSettings
    @State private var showFolderPicker = false

    var body: some View {
        TabView {
            GeneralTab()
                .tabItem { Label("General", systemImage: "gearshape") }
                .tag("general")

            DownloadsTab()
                .tabItem { Label("Downloads", systemImage: "arrow.down.circle") }
                .tag("downloads")

            AdvancedTab()
                .tabItem { Label("Advanced", systemImage: "wrench.and.screwdriver") }
                .tag("advanced")
        }
        .environmentObject(settings)
        .frame(width: 450)
        .padding()
    }
}

private struct GeneralTab: View {
    @EnvironmentObject var settings: AppSettings

    var body: some View {
        Form {
            Section("Quality & Format") {
                Picker("Default quality:", selection: $settings.defaultQuality) {
                    Text("4K").tag("4K")
                    Text("1080p").tag("1080p")
                    Text("720p").tag("720p")
                    Text("480p").tag("480p")
                    Text("360p").tag("360p")
                    Text("Audio Only").tag("Audio Only")
                }

                Picker("Default format:", selection: $settings.defaultFormat) {
                    Text("MP4").tag("mp4")
                    Text("MKV").tag("mkv")
                    Text("WebM").tag("webm")
                }
            }

            Section("Behaviour") {
                Toggle("Menu bar icon", isOn: $settings.menuBarIcon)
                Toggle("Monitor clipboard for video URLs", isOn: $settings.clipboardMonitoring)
            }
        }
        .formStyle(.grouped)
        .padding(.top)
    }
}

private struct DownloadsTab: View {
    @EnvironmentObject var settings: AppSettings
    @State private var showFolderPicker = false

    var body: some View {
        Form {
            Section("Save Location") {
                HStack {
                    Text(settings.saveFolderURL.path)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Button("Choose…") { showFolderPicker = true }
                }

                TextField("Filename template:", text: $settings.filenameTemplate)
                    .textFieldStyle(.roundedBorder)
                Text("Variables: %(title)s %(height)sp %(ext)s %(id)s")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Concurrency") {
                Stepper(
                    "Concurrent downloads: \(settings.maxConcurrentDownloads)",
                    value: $settings.maxConcurrentDownloads,
                    in: 1...10
                )
            }

            Section("Metadata") {
                Toggle("Embed thumbnail in file", isOn: $settings.embedThumbnail)
                Toggle("Embed subtitles", isOn: $settings.embedSubtitles)
            }
        }
        .formStyle(.grouped)
        .padding(.top)
        .fileImporter(isPresented: $showFolderPicker, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                _ = url.startAccessingSecurityScopedResource()
                settings.saveFolder = url.path
            }
        }
    }
}

private struct AdvancedTab: View {
    @EnvironmentObject var settings: AppSettings
    @State private var isUpdating = false
    @State private var updateMessage = ""

    var body: some View {
        Form {
            Section("Authentication") {
                Picker("Import cookies from:", selection: $settings.cookieBrowser) {
                    Text("None").tag("")
                    Text("Chrome").tag("chrome")
                    Text("Firefox").tag("firefox")
                    Text("Safari").tag("safari")
                    Text("Chromium").tag("chromium")
                    Text("Brave").tag("brave")
                }
            }

            Section("Updates") {
                HStack {
                    Button(isUpdating ? "Updating…" : "Update yt-dlp") {
                        updateYTDLP()
                    }
                    .disabled(isUpdating)

                    if !updateMessage.isEmpty {
                        Text(updateMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding(.top)
    }

    private func updateYTDLP() {
        isUpdating = true
        updateMessage = ""
        Task {
            do {
                try await YTDLPService.shared.updateBinary()
                updateMessage = "Updated successfully."
            } catch {
                updateMessage = error.localizedDescription
            }
            isUpdating = false
        }
    }
}
