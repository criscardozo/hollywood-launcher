import SwiftUI

/// The H▶ palette (design/icono): dark navy, off-white, and the play's pink and violets.
enum Theme {
    static let background = Color(hex: 0x0B0D12)
    static let card = Color(hex: 0x141A26)
    static let raised = Color(hex: 0x1C2434)
    static let text = Color(hex: 0xF2F7FF)
    static let dim = Color(hex: 0xF2F7FF).opacity(0.6)
    static let pink = Color(hex: 0xF472B6)
    static let violet = Color(hex: 0xA78BFA)
    static let deep = Color(hex: 0x7C3AED)
    static let accent = Color(hex: 0xC084FC)
    static let play = LinearGradient(colors: [pink, violet, deep], startPoint: .topLeading, endPoint: .bottomTrailing)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// A round key on the remote.
struct KeyButton: View {
    let symbol: String
    var label: String?
    var size: CGFloat = 58
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.36, weight: .semibold))
                .frame(width: size, height: size)
                .background(Theme.raised, in: Circle())
                .foregroundStyle(Theme.text)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label ?? symbol)
    }
}
