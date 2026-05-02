import SwiftUI

@main
struct NSLDownloadApp: App {
    @StateObject private var manager = DownloadManager.shared
    @StateObject private var settings = AppSettings.shared

    init() {
        NotificationService.shared.requestPermission()
    }

    var body: some Scene {
        mainWindow
        menuBarExtra
        settingsScene
    }

    private var mainWindow: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(manager)
                .environmentObject(settings)
        }
        .windowStyle(.titleBar)
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Add URL…") {
                    NSApp.sendAction(#selector(NSWindow.makeKeyAndOrderFront(_:)), to: nil, from: nil)
                }
                .keyboardShortcut("n", modifiers: .command)
            }
        }
    }

    private var menuBarExtra: some Scene {
        MenuBarExtra("NSL Download", systemImage: activeDownloadSystemImage) {
            MenuBarView()
                .environmentObject(manager)
        }
    }

    private var settingsScene: some Scene {
        Settings {
            PreferencesView()
                .environmentObject(settings)
        }
    }

    private var activeDownloadSystemImage: String {
        manager.items.filter { $0.status.isActive }.isEmpty
            ? "arrow.down.circle"
            : "arrow.down.circle.fill"
    }
}
