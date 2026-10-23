import RemoteKit
import ServiceManagement
import SwiftUI

/// Pairing until there is a TV, then the remote.
struct PanelRoot: View {
    @Environment(RemoteModel.self) private var model

    var body: some View {
        Group {
            switch model.phase {
            case .noTV, .pairing, .enteringCode: PairingPanel()
            default: RemotePanel()
            }
        }
        .padding(14)
        .frame(width: 320)
        .background(Theme.background)
    }
}

// MARK: Remote (layout A)

struct RemotePanel: View {
    @Environment(RemoteModel.self) private var model
    @State private var text = ""
    @State private var openAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        VStack(spacing: 12) {
            header
            if case .failed(let reason) = model.phase {
                failure(reason)
            } else {
                Group {
                    DPad { model.press($0) }
                    keys
                    apps
                    TextField("Escribir en la tele…", text: $text)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: text) { _, new in model.type(new) }
                        .onSubmit { model.press(.enter); text = "" }
                    Text("←↑↓→ mover · ↩ OK · esc atrás · h inicio · ⌥⌘H abrir")
                        .font(.system(size: 10.5)).foregroundStyle(Theme.dim.opacity(0.7))
                }
                .disabled(model.phase != .connected)
                .opacity(model.phase == .connected ? 1 : 0.45)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image("Logo").resizable().frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 1) {
                Text(model.tvName ?? "Hollywood").font(.system(size: 13, weight: .semibold))
                HStack(spacing: 5) {
                    Circle().fill(model.phase == .connected ? Color.green : Theme.dim).frame(width: 7, height: 7)
                    Text(status).font(.system(size: 11)).foregroundStyle(Theme.dim).lineLimit(1)
                }
            }
            Spacer()
            Menu {
                Button("Emparejar de nuevo") {
                    if let host = model.host { Task { await model.pair(host: host, name: model.tvName ?? "Hollywood") } }
                }
                Button("Olvidar la tele") { model.forgetTV() }
                Divider()
                Toggle("Abrir al iniciar sesión", isOn: $openAtLogin)
                Button("Salir") { NSApplication.shared.terminate(nil) }
            } label: {
                Image(systemName: "ellipsis")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            KeyButton(symbol: "power", label: "Encender o apagar", size: 32) { model.press(.power) }
                .foregroundStyle(Theme.pink)
        }
        .onChange(of: openAtLogin) { _, on in
            // Registering the app itself as a login item; it shows in System Settings → General → Login Items.
            try? on ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister()
        }
    }

    private var status: String {
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

    private var keys: some View {
        HStack(spacing: 12) {
            KeyButton(symbol: "arrow.uturn.backward", label: "Atrás", size: 38) { model.press(.back) }
            KeyButton(symbol: "house.fill", label: "Inicio", size: 38) { model.press(.home) }
            KeyButton(symbol: "minus", label: "Bajar volumen", size: 38) { model.press(.volumeDown) }
            KeyButton(symbol: "plus", label: "Subir volumen", size: 38) { model.press(.volumeUp) }
            KeyButton(symbol: "speaker.slash.fill", label: "Silencio", size: 38) { model.press(.mute) }
        }
    }

    private var apps: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 7), count: 4), spacing: 7) {
            ForEach(TVApp.house) { app in
                Button { model.launch(app) } label: {
                    Text(app.name)
                        .font(.system(size: 10.5, weight: .bold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background(LinearGradient(colors: app.colors.count > 1 ? app.colors : app.colors + app.colors,
                                                   startPoint: .topLeading, endPoint: .bottomTrailing),
                                    in: RoundedRectangle(cornerRadius: 9))
                        .foregroundStyle(app.textColor)
                }
                .buttonStyle(.plain)
            }
            Button { model.press(.settings) } label: {
                Text("Ajustes")
                    .font(.system(size: 10.5, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Theme.raised, in: RoundedRectangle(cornerRadius: 9))
                    .foregroundStyle(Theme.dim)
            }
            .buttonStyle(.plain)
        }
    }

    private func failure(_ reason: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "wifi.exclamationmark").font(.system(size: 30)).foregroundStyle(Theme.pink)
            Text("No se pudo conectar con la tele").font(.headline)
            Text(reason).font(.caption).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
            HStack {
                Button("Reintentar") { Task { await model.connectIfNeeded() } }
                Button("Emparejar de nuevo") {
                    if let host = model.host { Task { await model.pair(host: host, name: model.tvName ?? "Hollywood") } }
                }
            }
        }
        .padding(.vertical, 20)
    }
}

/// The d-pad: four arrows around a ring and OK in the middle, like the TV remote.
struct DPad: View {
    let press: (KeyCode) -> Void
    private let size: CGFloat = 176

    var body: some View {
        ZStack {
            Circle().fill(Theme.raised)
            arrow("chevron.up", .dpadUp).offset(y: -size * 0.36)
            arrow("chevron.down", .dpadDown).offset(y: size * 0.36)
            arrow("chevron.left", .dpadLeft).offset(x: -size * 0.36)
            arrow("chevron.right", .dpadRight).offset(x: size * 0.36)
            Button { press(.dpadCenter) } label: {
                Text("OK").font(.system(size: 14, weight: .bold))
                    .frame(width: size * 0.44, height: size * 0.44)
                    .background(Theme.card, in: Circle())
                    .overlay(Circle().stroke(Theme.accent.opacity(0.55), lineWidth: 2))
                    .foregroundStyle(Theme.text)
            }
            .buttonStyle(.plain)
        }
        .frame(width: size, height: size)
    }

    private func arrow(_ symbol: String, _ key: KeyCode) -> some View {
        Button { press(key) } label: {
            Image(systemName: symbol).font(.system(size: 16, weight: .bold))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .foregroundStyle(Theme.dim)
        }
        .buttonStyle(.plain)
    }
}

// MARK: Pairing

struct PairingPanel: View {
    @Environment(RemoteModel.self) private var model
    @State private var discovery = Discovery()
    @State private var manualHost = "192.168.0.4"
    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image("Logo").resizable().frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 1) {
                    Text("Hollywood").font(.system(size: 13, weight: .semibold))
                    Text("Emparejar con la tele").font(.system(size: 11)).foregroundStyle(Theme.dim)
                }
                Spacer()
                Button("Salir") { NSApplication.shared.terminate(nil) }.controlSize(.small)
            }
            switch model.phase {
            case .enteringCode: codeEntry
            case .pairing:
                HStack { ProgressView().controlSize(.small); Text("Hablando con la tele…").foregroundStyle(Theme.dim) }
            default: tvList
            }
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop() }
    }

    private var tvList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Elegí la tele: va a mostrar un código para escribir acá.").font(.caption).foregroundStyle(Theme.dim)
            if let error = model.pairingError {
                Text(error).font(.caption).foregroundStyle(Theme.pink)
            }
            if discovery.tvs.isEmpty {
                HStack { ProgressView().controlSize(.small); Text("Buscando en la red…").font(.caption).foregroundStyle(Theme.dim) }
            }
            ForEach(discovery.tvs) { tv in
                Button { Task { await model.pair(host: tv.host, name: tv.name) } } label: {
                    HStack {
                        Image(systemName: "tv")
                        VStack(alignment: .leading) {
                            Text(tv.name)
                            Text(tv.host).font(.caption).foregroundStyle(Theme.dim)
                        }
                        Spacer()
                    }
                    .padding(8)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
            Text("O por dirección").font(.caption).foregroundStyle(Theme.dim).padding(.top, 4)
            HStack {
                TextField("IP de la tele", text: $manualHost).textFieldStyle(.roundedBorder)
                Button("Emparejar") { Task { await model.pair(host: manualHost, name: "Hollywood") } }
                    .disabled(manualHost.isEmpty)
            }
        }
    }

    private var codeEntry: some View {
        VStack(spacing: 10) {
            Text("Escribí el código que muestra la tele").font(.headline)
            TextField("", text: $code)
                .font(.system(size: 28, weight: .bold, design: .monospaced))
                .multilineTextAlignment(.center)
                .textFieldStyle(.roundedBorder)
                .onChange(of: code) { _, new in code = String(new.uppercased().filter { $0.isHexDigit }.prefix(6)) }
                .onSubmit { if code.count == 6 { submit() } }
            Text("Son 6 caracteres, números y letras de la A a la F.").font(.caption).foregroundStyle(Theme.dim)
            HStack {
                Button("Cancelar") { model.cancelPairing() }
                Button("Emparejar") { submit() }.keyboardShortcut(.defaultAction).disabled(code.count != 6)
            }
        }
    }

    private func submit() {
        model.submitCode(code)
        code = ""
    }
}
