import SwiftUI

/// Temporary root until onboarding and the drive screen land.
struct RootView: View {
    let configuration: AppConfiguration

    var body: some View {
        ContentUnavailableView {
            Label("x_radar", systemImage: "dot.radiowaves.left.and.right")
        } description: {
            Text("Squelette iOS prêt. Backend : \(configuration.backend.baseURL.host() ?? "—")")
        }
        .accessibilityIdentifier("root.placeholder")
    }
}

#Preview {
    RootView(configuration: AppConfiguration(info: [:]))
}
