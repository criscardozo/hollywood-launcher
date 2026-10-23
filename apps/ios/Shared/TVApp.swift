import SwiftUI

/// The house apps (docs/tele.md), opened on the TV through Remote v2's app-link launch.
struct TVApp: Identifiable, Hashable {
    let name: String
    let package: String
    let background: [UInt32]
    let foreground: UInt32
    var id: String { package }

    var colors: [Color] { background.map { Color(hex: $0) } }
    var textColor: Color { Color(hex: foreground) }

    /// market://launch opens an installed app by package; YouTube also takes its own URL.
    var link: String {
        package == "com.google.android.youtube.tv" ? "https://www.youtube.com" : "market://launch?id=\(package)"
    }

    static let house: [TVApp] = [
        TVApp(name: "YouTube", package: "com.google.android.youtube.tv", background: [0x0F0F0F], foreground: 0xFF2D2D),
        TVApp(name: "Jellyfin", package: "com.github.damontecres.wholphin", background: [0x6A2C91, 0x00A4DC], foreground: 0xFFFFFF),
        TVApp(name: "Prime Video", package: "com.amazon.amazonvideo.livingroom", background: [0x0F171E], foreground: 0x1FA2FF),
        TVApp(name: "Disney+", package: "com.disney.disneyplus", background: [0x0B1F6B, 0x1A4FD6], foreground: 0xFFFFFF),
        TVApp(name: "Max", package: "com.wbd.stream", background: [0x002BE7], foreground: 0xFFFFFF),
        TVApp(name: "Spotify", package: "com.spotify.tv.android", background: [0x121212], foreground: 0x1ED760),
        TVApp(name: "9Now", package: "au.com.ninenow.ctv", background: [0x0A2A66], foreground: 0xFFFFFF),
        TVApp(name: "7plus", package: "com.swm.live", background: [0xE2001A], foreground: 0xFFFFFF),
        TVApp(name: "10 Play", package: "au.com.tenplay", background: [0x111111], foreground: 0xFF2A3C),
        TVApp(name: "SBS", package: "com.sbs.ondemand.tv", background: [0xFFCC00], foreground: 0x111111),
        TVApp(name: "iview", package: "au.net.abc.iview", background: [0x0B0B0B], foreground: 0x2FF3E0),
    ]
}
