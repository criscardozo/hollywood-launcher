import AppKit
import Carbon.HIToolbox
import RemoteKit
import SwiftUI

/// The Mac remote: an «H▶» in the menu bar (LSUIElement, no Dock icon) that opens a panel with the controls
/// (layout A of docs/mockups/mac.html). It shares the model, discovery and app list with the iPhone remote
/// (Shared/) and talks to the TV through RemoteKit. ⌥⌘H opens it from anywhere; while it is open the
/// keyboard is the remote.
@main
struct HollywoodMenuApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        // Everything lives in the status item's popover; an app needs at least one scene.
        Settings { EmptyView() }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSPopoverDelegate {
    private let model = RemoteModel()
    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    private var hotKey: HotKey?
    private var keyMonitor: Any?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // A MenuBarExtra cannot be opened from code, so the global shortcut needs a status item of our own.
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = NSImage(named: "MenuIcon")
        item.button?.image?.isTemplate = true
        item.button?.toolTip = "Hollywood"
        item.button?.target = self
        item.button?.action = #selector(toggle)
        statusItem = item

        let host = NSHostingController(rootView: PanelRoot().environment(model).preferredColorScheme(.dark))
        host.sizingOptions = .preferredContentSize
        popover.contentViewController = host
        popover.behavior = .transient
        popover.animates = false
        popover.delegate = self

        hotKey = HotKey(keyCode: UInt32(kVK_ANSI_H), modifiers: UInt32(cmdKey | optionKey)) { [weak self] in
            self?.toggle()
        }
        // `-open YES` (with `-demo YES` for made-up state): the panel open at launch.
        // The status item's button is not on screen yet at launch: show the popover a moment later.
        if UserDefaults.standard.bool(forKey: "open") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.toggle() }
        }
        // `-snapshot <path.png>`: draws the panel to a file and quits, to check it without screen recording.
        if let path = UserDefaults.standard.string(forKey: "snapshot") {
            let renderer = ImageRenderer(content: PanelRoot().environment(model).environment(\.colorScheme, .dark))
            renderer.scale = 2
            if let image = renderer.nsImage, let tiff = image.tiffRepresentation,
               let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                try? png.write(to: URL(fileURLWithPath: path))
            }
            NSApp.terminate(nil)
        }
    }

    @objc func toggle() {
        if popover.isShown {
            popover.performClose(nil)
        } else if let button = statusItem?.button {
            NSApp.activate()
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
            Task { await model.connectIfNeeded() }
        }
    }

    func popoverDidShow(_ notification: Notification) {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            return self.handle(event) ? nil : event
        }
    }

    func popoverDidClose(_ notification: Notification) {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
    }

    /// The keyboard as the remote, unless a text field is being typed in (then the keys are text).
    private func handle(_ event: NSEvent) -> Bool {
        guard model.phase == .connected else { return false }
        if event.window?.firstResponder is NSTextView { return false }
        if event.modifierFlags.contains(.command) { return false }
        let key: KeyCode? = switch Int(event.keyCode) {
        case kVK_LeftArrow: .dpadLeft
        case kVK_RightArrow: .dpadRight
        case kVK_UpArrow: .dpadUp
        case kVK_DownArrow: .dpadDown
        case kVK_Return, kVK_ANSI_KeypadEnter: .dpadCenter
        case kVK_Escape, kVK_Delete: .back
        case kVK_Space: .mediaPlayPause
        default:
            switch event.charactersIgnoringModifiers {
            case "h": .home
            case "+", "=": .volumeUp
            case "-": .volumeDown
            case "m": .mute
            default: nil
            }
        }
        guard let key else { return false }
        model.press(key)
        return true
    }
}

/// A system-wide shortcut through Carbon's RegisterEventHotKey: unlike a global NSEvent monitor it does
/// not need the Accessibility permission. One per app is all this needs.
final class HotKey {
    private var ref: EventHotKeyRef?
    nonisolated(unsafe) private static var action: (@MainActor () -> Void)?

    init(keyCode: UInt32, modifiers: UInt32, action: @escaping @MainActor () -> Void) {
        HotKey.action = action
        var pressed = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            // Carbon delivers hot keys on the main thread.
            MainActor.assumeIsolated { HotKey.action?() }
            return noErr
        }, 1, &pressed, nil, nil)
        let id = EventHotKeyID(signature: OSType(0x484C_5744), id: 1) // 'HLWD'
        RegisterEventHotKey(keyCode, modifiers, id, GetApplicationEventTarget(), 0, &ref)
    }

    deinit {
        if let ref { UnregisterEventHotKey(ref) }
    }
}
