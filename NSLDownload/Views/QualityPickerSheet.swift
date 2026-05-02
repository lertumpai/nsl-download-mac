import SwiftUI

struct QualityPickerSheet: View {
    let item: DownloadItem
    let metadata: VideoMetadata
    let onDownload: (VideoFormat) -> Void
    let onCancel: () -> Void

    @State private var selectedFormatID: String
    @State private var outputFormat: String

    @EnvironmentObject private var settings: AppSettings

    private var formats: [VideoFormat] { metadata.displayFormats }

    init(item: DownloadItem, metadata: VideoMetadata, onDownload: @escaping (VideoFormat) -> Void, onCancel: @escaping () -> Void) {
        self.item = item
        self.metadata = metadata
        self.onDownload = onDownload
        self.onCancel = onCancel
        _selectedFormatID = State(initialValue: metadata.defaultFormat?.id ?? "")
        _outputFormat = State(initialValue: "mp4")
    }

    private var selectedFormat: VideoFormat? {
        formats.first { $0.id == selectedFormatID }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header
            HStack(alignment: .top, spacing: 12) {
                if let thumbURL = metadata.thumbnail, let url = URL(string: thumbURL) {
                    AsyncImage(url: url) { image in
                        image.resizable().aspectRatio(contentMode: .fill)
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.2))
                    }
                    .frame(width: 120, height: 68)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(metadata.title)
                        .font(.headline)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: 10) {
                        if let duration = metadata.duration {
                            Label(formatDuration(duration), systemImage: "clock")
                        }
                        Label(metadata.extractor, systemImage: "globe")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }

            Divider()

            Text("Select Format:")
                .font(.subheadline.weight(.medium))

            VStack(spacing: 2) {
                ForEach(formats) { format in
                    FormatRow(format: format, isSelected: selectedFormatID == format.id)
                        .contentShape(Rectangle())
                        .onTapGesture { selectedFormatID = format.id }
                }
            }

            Divider()

            HStack {
                Text("Output:")
                Picker("", selection: $outputFormat) {
                    Text("MP4").tag("mp4")
                    Text("MKV").tag("mkv")
                    Text("WebM").tag("webm")
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 80)

                Spacer()

                Button("Cancel", action: onCancel)
                    .keyboardShortcut(.escape, modifiers: [])

                Button("Download") {
                    if let format = selectedFormat {
                        onDownload(format)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(selectedFormat == nil)
                .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(20)
        .frame(width: 420)
        .onAppear {
            // Apply default quality preference
            let preferred = settings.defaultQuality
            let match = formats.first {
                $0.displayResolution.lowercased() == preferred.lowercased()
            }
            if let match { selectedFormatID = match.id }
            outputFormat = settings.defaultFormat
        }
    }

    private func formatDuration(_ seconds: Double) -> String {
        let t = Int(seconds)
        let h = t / 3600, m = (t % 3600) / 60, s = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

private struct FormatRow: View {
    let format: VideoFormat
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                .font(.system(size: 16))

            VStack(alignment: .leading, spacing: 1) {
                Text(format.displayResolution)
                    .fontWeight(isSelected ? .semibold : .regular)
                if !format.displayCodec.isEmpty {
                    Text(format.displayCodec)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if !format.displaySize.isEmpty {
                Text(format.displaySize)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .background(isSelected ? Color.accentColor.opacity(0.08) : Color.clear)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
