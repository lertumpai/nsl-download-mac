import SwiftUI
import AppKit

struct MenuBarView: View {
    @EnvironmentObject var manager: DownloadManager
    @State private var urlText = ""

    private var active: [DownloadItem] {
        manager.items.filter { $0.status.isActive }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                TextField("Paste URL…", text: $urlText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submitURL() }

                Button("Add") { submitURL() }
                    .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(.horizontal, 8)
            .padding(.top, 8)

            Divider()

            if active.isEmpty {
                Text("No active downloads")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            } else {
                ForEach(active.prefix(5)) { item in
                    MenuBarItemRow(item: item)
                }
                if active.count > 5 {
                    Text("+ \(active.count - 5) more…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 12)
                }
            }

            Divider()

            Button("Open NSL Download") {
                NSApp.activate(ignoringOtherApps: true)
            }
            Button("Quit") {
                NSApp.terminate(nil)
            }
        }
        .frame(width: 280)
        .padding(.bottom, 8)
    }

    private func submitURL() {
        let url = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        manager.addURL(url)
        urlText = ""
        NSApp.activate(ignoringOtherApps: true)
    }
}

private struct MenuBarItemRow: View {
    @ObservedObject var item: DownloadItem

    var body: some View {
        HStack(spacing: 8) {
            statusIcon
            Text(item.title)
                .lineLimit(1)
                .font(.caption)
            Spacer()
            if case .downloading(let p, _, _, _, _) = item.status {
                Text(String(format: "%.0f%%", p * 100))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch item.status {
        case .downloading:
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Color.accentColor)
                .font(.caption)
        case .muxing:
            Image(systemName: "arrow.triangle.merge")
                .foregroundStyle(.orange)
                .font(.caption)
        default:
            Image(systemName: "clock")
                .foregroundStyle(.secondary)
                .font(.caption)
        }
    }
}
