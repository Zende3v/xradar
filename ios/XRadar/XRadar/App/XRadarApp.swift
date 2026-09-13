import SwiftUI

@main
struct XRadarApp: App {
    private let configuration = AppConfiguration.current

    var body: some Scene {
        WindowGroup {
            RootView(configuration: configuration)
        }
    }
}
