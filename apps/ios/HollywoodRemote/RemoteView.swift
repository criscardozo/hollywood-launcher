import RemoteKit
import SwiftUI

/// The remote: what the TV is showing, a touch surface for the d-pad, the keys, and the apps.
struct RemoteView: View {
    @Environment(RemoteModel.self) private var model
    @State private var showKeyboard = false
    @State private var showApps = false

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 18) {
                header
                if case .failed(let reason) = model.phase {
                    failure(reason)
                } else {
                    Trackpad(
                        onMove: { model.press($0) },
                        onSelect: { model.press(.dpadCenter) },
                        onLongSelect: { model.press(.dpadCenter, .startLong); model.press(.dpadCenter, .endLong) }
                    )
                    .opacity(model.phase == .connected ? 1 : 0.4)
                    keys
                    appsStrip
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .sheet(isPresented: $showKeyboard) { KeyboardSheet().presentationDetents([.height(220)]) }
        .sheet(isPresented: $showApps) { AppsSheet().presentationDetents([.medium, .large]) }
        .task { await model.connectIfNeeded() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image("Logo").resizable().frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(model.tvName ?? "Hollywood").font(.headline)
                Text(statusLine).font(.caption).foregroundStyle(Theme.dim).lineLimit(1)
            }
            Spacer()
            Menu {
                Button("Emparejar de nuevo", systemImage: "link") {
                    if let host = model.host { Task { await model.pair(host: host, name: model.tvName ?? "Hollywood") } }
                }
                Button("Olvidar la tele", systemImage: "trash", role: .destructive) { model.forgetTV() }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title2).foregroundStyle(Theme.dim)
            }
            KeyButton(symbol: "power", label: "Encender o apagar", size: 44) { model.press(.power) }
        }
    }

    private var statusLine: String {
        switch model.phase {
        case .connected:
            var parts: [String] = []
            if model.state.isOn == false { parts.append("En reposo") }
            if let app = model.currentAppName { parts.append(app) }
            if let level = model.state.volumeLevel, let max = model.state.volumeMax, max > 0 {
                parts.append(model.state.muted == true ? "Silencio" : "Volumen \(level * 100 / max)%")
            }
            return parts.isEmpty ? "Conectado" : parts.joined(separator: " · ")
        case .connecting: return "Conectando…"
        case .failed: return "Sin conexión"
        default: return ""
        }
    }

    private func failure(_ reason: String) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "wifi.exclamationmark").font(.system(size: 44)).foregroundStyle(Theme.pink)
            Text("No se pudo conectar con la tele").font(.headline)
            Text(reason).font(.footnote).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
            HStack {
                Button("Reintentar") { Task { await model.connectIfNeeded() } }.buttonStyle(.borderedProminent)
                Button("Emparejar de nuevo") {
                    if let host = model.host { Task { await model.pair(host: host, name: model.tvName ?? "Hollywood") } }
                }
                .buttonStyle(.bordered)
            }
            Spacer()
        }
    }

    private var keys: some View {
        HStack(alignment: .center, spacing: 0) {
            VStack(spacing: 14) {
                KeyButton(symbol: "arrow.uturn.backward", label: "Atrás") { model.press(.back) }
                KeyButton(symbol: "house.fill", label: "Inicio") { model.press(.home) }
            }
            Spacer()
            VStack(spacing: 14) {
                KeyButton(symbol: "playpause.fill", label: "Reproducir o pausar") { model.press(.mediaPlayPause) }
                KeyButton(symbol: "keyboard", label: "Teclado") { showKeyboard = true }
            }
            Spacer()
            VStack(spacing: 14) {
                KeyButton(symbol: "speaker.slash.fill", label: "Silencio") { model.press(.mute) }
                KeyButton(symbol: "square.grid.2x2.fill", label: "Apps") { showApps = true }
            }
            Spacer()
            VolumeRocker(up: { model.press(.volumeUp) }, down: { model.press(.volumeDown) })
        }
    }

    private var appsStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(TVApp.house) { app in
                    AppTile(app: app, height: 56) { model.launch(app) }
                }
            }
        }
        .padding(.bottom, 8)
    }
}

/// Swipe to move, tap to select, hold for a long press. Each 60 pt of travel is one step, so a long
/// swipe scrolls a row.
struct Trackpad: View {
    let onMove: (KeyCode) -> Void
    let onSelect: () -> Void
    let onLongSelect: () -> Void
    @State private var travelled: CGSize = .zero
    @State private var dragging = false
    private let step: CGFloat = 60

    var body: some View {
        RoundedRectangle(cornerRadius: 28)
            .fill(Theme.card)
            .overlay {
                RoundedRectangle(cornerRadius: 28).strokeBorder(dragging ? AnyShapeStyle(Theme.play) : AnyShapeStyle(Theme.raised), lineWidth: 2)
            }
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "hand.draw").font(.title)
                    Text("Deslizá para moverte · tocá para elegir").font(.caption)
                }
                .foregroundStyle(Theme.dim.opacity(dragging ? 0.2 : 0.8))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        dragging = true
                        let dx = value.translation.width - travelled.width
                        let dy = value.translation.height - travelled.height
                        if abs(dx) >= step, abs(dx) > abs(dy) {
                            onMove(dx > 0 ? .dpadRight : .dpadLeft)
                            travelled.width += dx > 0 ? step : -step
                        } else if abs(dy) >= step {
                            onMove(dy > 0 ? .dpadDown : .dpadUp)
                            travelled.height += dy > 0 ? step : -step
                        }
                    }
                    .onEnded { value in
                        // A short flick that never reached a full step still counts as one.
                        if travelled == .zero {
                            let t = value.translation
                            if abs(t.width) > abs(t.height) { onMove(t.width > 0 ? .dpadRight : .dpadLeft) }
                            else { onMove(t.height > 0 ? .dpadDown : .dpadUp) }
                        }
                        travelled = .zero
                        dragging = false
                    }
            )
            .simultaneousGesture(TapGesture().onEnded { onSelect() })
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in onLongSelect() })
            .frame(maxHeight: .infinity)
    }
}

struct VolumeRocker: View {
    let up: () -> Void
    let down: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: up) { Image(systemName: "plus").frame(width: 58, height: 64) }
                .accessibilityLabel("Subir volumen")
            Divider().frame(width: 30).overlay(Theme.dim.opacity(0.3))
            Button(action: down) { Image(systemName: "minus").frame(width: 58, height: 64) }
                .accessibilityLabel("Bajar volumen")
        }
        .font(.system(size: 20, weight: .semibold))
        .foregroundStyle(Theme.text)
        .background(Theme.raised, in: Capsule())
        .buttonStyle(.plain)
    }
}

struct AppTile: View {
    let app: TVApp
    var height: CGFloat = 72
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(app.name)
                .font(.system(size: height * 0.27, weight: .heavy))
                .foregroundStyle(app.textColor)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 10)
                .frame(width: height * 16 / 9, height: height)
                .background(LinearGradient(colors: app.colors, startPoint: .topLeading, endPoint: .bottomTrailing),
                            in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

/// All the house apps, bigger.
struct AppsSheet: View {
    @Environment(RemoteModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 12) {
                ForEach(TVApp.house) { app in
                    AppTile(app: app, height: 80) { model.launch(app); dismiss() }
                }
            }
            .padding(20)
        }
        .background(Theme.background)
    }
}

/// Writes into the TV's focused text field as you type (the TV keyboard must be open).
struct KeyboardSheet: View {
    @Environment(RemoteModel.self) private var model
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Escribir en la tele").font(.headline)
            TextField("Buscar…", text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .padding(12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .onChange(of: text) { _, new in model.type(new) }
                .onSubmit { model.press(.enter) }
            Text("Se escribe en el campo de texto que esté abierto en la tele. Enter lo envía.")
                .font(.caption).foregroundStyle(Theme.dim)
        }
        .padding(20)
        .onAppear { focused = true }
        .background(Theme.background)
    }
}
