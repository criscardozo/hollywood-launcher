import Foundation

/// Android key codes the remote sends (android.view.KeyEvent, also remotemessage.proto's RemoteKeyCode).
public enum KeyCode: Int, Sendable, CaseIterable {
    case home = 3
    case back = 4
    case dpadUp = 19
    case dpadDown = 20
    case dpadLeft = 21
    case dpadRight = 22
    case dpadCenter = 23
    case volumeUp = 24
    case volumeDown = 25
    case power = 26
    case enter = 66
    case del = 67
    case menu = 82
    case search = 84
    case mediaPlayPause = 85
    case mediaStop = 86
    case mediaNext = 87
    case mediaPrevious = 88
    case mediaRewind = 89
    case mediaFastForward = 90
    case mute = 164
    case info = 165
    case channelUp = 166
    case channelDown = 167
    case guide = 172
    case captions = 175
    case tvInput = 178
    case settings = 176

    /// Names as in KeyEvent, without the KEYCODE_ prefix (for the command line).
    public init?(name: String) {
        let wanted = name.uppercased().replacingOccurrences(of: "KEYCODE_", with: "")
        guard let match = Self.allCases.first(where: { $0.name == wanted }) else { return nil }
        self = match
    }

    public var name: String {
        switch self {
        case .home: "HOME"
        case .back: "BACK"
        case .dpadUp: "DPAD_UP"
        case .dpadDown: "DPAD_DOWN"
        case .dpadLeft: "DPAD_LEFT"
        case .dpadRight: "DPAD_RIGHT"
        case .dpadCenter: "DPAD_CENTER"
        case .volumeUp: "VOLUME_UP"
        case .volumeDown: "VOLUME_DOWN"
        case .power: "POWER"
        case .enter: "ENTER"
        case .del: "DEL"
        case .menu: "MENU"
        case .search: "SEARCH"
        case .mediaPlayPause: "MEDIA_PLAY_PAUSE"
        case .mediaStop: "MEDIA_STOP"
        case .mediaNext: "MEDIA_NEXT"
        case .mediaPrevious: "MEDIA_PREVIOUS"
        case .mediaRewind: "MEDIA_REWIND"
        case .mediaFastForward: "MEDIA_FAST_FORWARD"
        case .mute: "VOLUME_MUTE"
        case .info: "INFO"
        case .channelUp: "CHANNEL_UP"
        case .channelDown: "CHANNEL_DOWN"
        case .guide: "GUIDE"
        case .captions: "CAPTIONS"
        case .tvInput: "TV_INPUT"
        case .settings: "SETTINGS"
        }
    }
}
