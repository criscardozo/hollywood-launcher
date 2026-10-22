import SwiftUI

/// Choose the TV and pair: the TV shows a six-character code, typed here once.
struct PairingView: View {
    @Environment(RemoteModel.self) private var model
    @State private var discovery = Discovery()
    @State private var manualHost = "192.168.0.4"
    @State private var code = ""
    @FocusState private var codeFocused: Bool

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                switch model.phase {
                case .enteringCode: codeEntry
                case .pairing: progress("Hablando con la tele…")
                default: tvList
                }
            }
            .navigationTitle("Emparejar")
            .toolbar {
                if model.phase == .enteringCode || model.phase == .pairing {
                    Button("Cancelar") { model.cancelPairing() }
                }
            }
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop() }
    }

    private var tvList: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Image("Logo").resizable().frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Hollywood Remote").font(.headline)
                        Text("Elegí la tele: va a mostrar un código para escribir acá.").font(.subheadline).foregroundStyle(Theme.dim)
                    }
                }
                .listRowBackground(Color.clear)
            }
            if let error = model.pairingError {
                Section { Text(error).foregroundStyle(Theme.pink) }
            }
            Section("En la red") {
                if discovery.tvs.isEmpty {
                    HStack { ProgressView(); Text("Buscando…").foregroundStyle(Theme.dim) }
                }
                ForEach(discovery.tvs) { tv in
                    Button {
                        Task { await model.pair(host: tv.host, name: tv.name) }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(tv.name).foregroundStyle(Theme.text)
                            Text(tv.host).font(.caption).foregroundStyle(Theme.dim)
                        }
                    }
                }
            }
            Section("O por dirección") {
                TextField("IP de la tele", text: $manualHost)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Emparejar con \(manualHost)") {
                    Task { await model.pair(host: manualHost, name: "Hollywood") }
                }
                .disabled(manualHost.isEmpty)
            }
        }
        .scrollContentBackground(.hidden)
    }

    private var codeEntry: some View {
        VStack(spacing: 24) {
            Text("Escribí el código que muestra la tele").font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            TextField("", text: $code)
                .font(.system(size: 40, weight: .bold, design: .monospaced))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .focused($codeFocused)
                .padding(.vertical, 14)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
                .onChange(of: code) { _, new in
                    code = String(new.uppercased().filter { $0.isHexDigit }.prefix(6))
                }
            Text("Son 6 caracteres, números y letras de la A a la F.").font(.footnote).foregroundStyle(Theme.dim)
            Button {
                model.submitCode(code)
                code = ""
            } label: {
                Text("Emparejar").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(Theme.play, in: RoundedRectangle(cornerRadius: 16))
                    .foregroundStyle(.white)
            }
            .disabled(code.count != 6)
            .opacity(code.count == 6 ? 1 : 0.5)
        }
        .padding(24)
        .onAppear { codeFocused = true }
    }

    private func progress(_ text: String) -> some View {
        VStack(spacing: 16) {
            ProgressView().controlSize(.large)
            Text(text).foregroundStyle(Theme.dim)
        }
    }
}
