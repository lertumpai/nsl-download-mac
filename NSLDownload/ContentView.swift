import SwiftUI

struct ContentView: View {
    @EnvironmentObject var manager: DownloadManager
    @EnvironmentObject var settings: AppSettings

    @State private var urlText = ""
    @State private var isProcessing = false

    var body: some View {
        VStack(spacing: 0) {
            // URL Input Bar
            URLInputView(urlText: $urlText, isProcessing: $isProcessing, onSubmit: submitURL)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

            Divider()

            if manager.items.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                        if !manager.queueItems.isEmpty {
                            Section {
                                ForEach(manager.queueItems) { item in
                                    DownloadItemRow(item: item)
                                        .padding(.horizontal, 12)
                                    Divider().padding(.leading, 84)
                                }
                            } header: {
                                SectionHeader(title: "QUEUE", count: manager.queueItems.count) {
                                    // no action
                                }
                            }
                        }

                        if !manager.completedItems.isEmpty {
                            Section {
                                ForEach(manager.completedItems) { item in
                                    CompletedItemRow(item: item)
                                        .padding(.horizontal, 12)
                                    Divider().padding(.leading, 34)
                                }
                            } header: {
                                SectionHeader(title: "COMPLETED", count: manager.completedItems.count) {
                                    manager.clearCompleted()
                                }
                            }
                        }
                    }
                }
            }
        }
        .frame(minWidth: 600, minHeight: 420)
        .sheet(item: $manager.pendingFormatItem) { item in
            if case .awaitingFormat(let metadata) = item.status {
                QualityPickerSheet(
                    item: item,
                    metadata: metadata,
                    onDownload: { format in
                        manager.startDownload(item: item, format: format, metadata: metadata)
                        advancePendingFormat(after: item)
                    },
                    onCancel: {
                        manager.cancel(item)
                        advancePendingFormat(after: item)
                    }
                )
                .environmentObject(settings)
            }
        }
        .onAppear {
            if settings.clipboardMonitoring {
                manager.setupClipboardMonitoring()
            }
        }
        .onChange(of: settings.clipboardMonitoring) { enabled in
            if enabled { manager.setupClipboardMonitoring() } else { manager.stopClipboardMonitoring() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 52))
                .foregroundStyle(.secondary.opacity(0.5))
            Text("Paste a video URL above to get started")
                .foregroundStyle(.secondary)
            Text("Supports YouTube, Facebook, VK, TikTok, and 1,000+ more sites")
                .font(.caption)
                .foregroundStyle(Color.secondary.opacity(0.7))
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func submitURL() {
        let url = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        manager.addURL(url)
        urlText = ""
    }

    private func advancePendingFormat(after item: DownloadItem) {
        manager.pendingFormatItem = manager.items.first { other in
            guard other.id != item.id else { return false }
            if case .awaitingFormat = other.status { return true }
            return false
        }
    }
}

private struct SectionHeader: View {
    let title: String
    let count: Int
    let onClear: () -> Void

    var body: some View {
        HStack {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text("\(count)")
                .font(.caption)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(Color.secondary.opacity(0.15))
                .clipShape(Capsule())

            Spacer()

            if title == "COMPLETED" {
                Button("Clear", action: onClear)
                    .font(.caption)
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.bar)
    }
}
