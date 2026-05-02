import SwiftUI
import AppKit

struct CompletedItemRow: View {
    @ObservedObject var item: DownloadItem
    @EnvironmentObject var manager: DownloadManager

    var body: some View {
        HStack(spacing: 10) {
            statusIcon

            Text(item.title)
                .lineLimit(1)
                .fontWeight(.medium)

            Spacer(minLength: 4)

            if case .completed(let url) = item.status {
                HStack(spacing: 6) {
                    Button("Open File") { NSWorkspace.shared.open(url) }
                        .buttonStyle(.plain)
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)

                    Button("Show in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting([url])
                    }
                    .buttonStyle(.plain)
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                }
            }

            if case .failed = item.status {
                Button("Retry") { manager.retry(item) }
                    .buttonStyle(.plain)
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
            }

            Button { manager.remove(item) } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("Remove")
        }
        .padding(.vertical, 5)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch item.status {
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
        case .cancelled:
            Image(systemName: "minus.circle.fill")
                .foregroundStyle(.secondary)
        default:
            EmptyView()
        }
    }
}
