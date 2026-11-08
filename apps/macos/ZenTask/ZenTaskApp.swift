import SwiftUI

@main
struct ZenTaskApp: App {
    @State private var model: AppModel

    init() {
        if let dir = ProcessInfo.processInfo.environment["ZENTASK_SNAPSHOT"] {
            Snapshots.render(to: URL(fileURLWithPath: dir))
            exit(0)
        }
        let model = AppModel.live()
        _model = State(initialValue: model)
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            model.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
        .defaultSize(width: 1100, height: 720)

        Settings {
            SettingsView()
                .environment(model)
        }
    }
}
