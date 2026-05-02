import SwiftUI

struct DownloadItemRow: View {
    @ObservedObject var item: DownloadItem
    @EnvironmentObject var manager: DownloadManager

    var body: some View {
        HStack(spacing: 10) {
            thumbnailView
                .frame(width: 60, height: 34)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .lineLimit(1)
                    .fontWeight(.medium)
                statusView
            }

            Spacer(minLength: 4)

            actionButtons
        }
        .padding(.vertical, 5)
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if let meta = item.cachedMetadata,
           let thumbStr = meta.thumbnail,
           let url = URL(string: thumbStr) {
            AsyncImage(url: url) { image in
                image.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                placeholderIcon
            }
        } else {
            placeholderIcon
        }
    }

    private var placeholderIcon: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.secondary.opacity(0.12))
            Image(systemName: "play.rectangle")
                .foregroundStyle(.secondary)
                .font(.system(size: 14))
        }
    }

    @ViewBuilder
    private var statusView: some View {
        switch item.status {
        case .queued:
            Label("Queued", systemImage: "clock")
                .font(.caption)
                .foregroundStyle(.secondary)

        case .fetchingMetadata:
            HStack(spacing: 4) {
                ProgressView().scaleEffect(0.55).frame(width: 12, height: 12)
                Text("Fetching info…")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

        case .awaitingFormat:
            Label("Select a format to download", systemImage: "square.and.arrow.down")
                .font(.caption)
                .foregroundStyle(Color.accentColor)

        case .downloading(let progress, let speed, let eta, let downloaded, let total):
            VStack(alignment: .leading, spacing: 2) {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .frame(maxWidth: 300)

                HStack(spacing: 6) {
                    Text(String(format: "%.1f%%", progress * 100))
                    if !speed.isEmpty {
                        Text("·").foregroundStyle(.secondary)
                        Text(speed)
                    }
                    if !downloaded.isEmpty && !total.isEmpty {
                        Text("·").foregroundStyle(.secondary)
                        Text("\(downloaded) / \(total)")
                    }
                    Spacer()
                    if !eta.isEmpty {
                        Text("ETA \(eta)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

        case .muxing:
            HStack(spacing: 4) {
                ProgressView().scaleEffect(0.55).frame(width: 12, height: 12)
                Text("Merging audio + video…")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

        case .paused:
            Label("Paused", systemImage: "pause.circle")
                .font(.caption)
                .foregroundStyle(.orange)

        case .failed(let err):
            Label(err, systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(.red)
                .lineLimit(2)

        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        HStack(spacing: 6) {
            switch item.status {
            case .downloading:
                Button { manager.pause(item) } label: {
                    Image(systemName: "pause.fill").frame(width: 16)
                }
                .buttonStyle(.plain)
                .help("Pause")

            case .paused:
                Button { manager.resume(item) } label: {
                    Image(systemName: "play.fill").frame(width: 16)
                }
                .buttonStyle(.plain)
                .help("Resume")

            case .failed:
                Button { manager.retry(item) } label: {
                    Image(systemName: "arrow.clockwise").frame(width: 16)
                }
                .buttonStyle(.plain)
                .help("Retry")

            case .awaitingFormat:
                Button { manager.pendingFormatItem = item } label: {
                    Image(systemName: "arrow.down.circle").frame(width: 16)
                }
                .buttonStyle(.plain)
                .help("Select format")

            default:
                EmptyView()
            }

            Button { manager.remove(item) } label: {
                Image(systemName: "xmark").frame(width: 16)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("Remove")
        }
        .font(.system(size: 12))
    }
}
