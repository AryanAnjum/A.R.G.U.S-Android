import SwiftUI

extension Color {
    static let argusGreen = Color(red: 0, green: 0.902, blue: 0.463)       // #00E676
    static let argusTeal = Color(red: 0.114, green: 0.914, blue: 0.714)    // #1DE9B6
    static let argusSubtext = Color(red: 0.541, green: 0.541, blue: 0.6)   // #8A8A99
    static let argusWarn = Color(red: 1, green: 0.42, blue: 0.29)
    static let glass = Color(red: 0.05, green: 0.05, blue: 0.07).opacity(0.72)
}

private let argusGradient = LinearGradient(colors: [.argusGreen, .argusTeal], startPoint: .leading, endPoint: .trailing)

struct ContentView: View {
    @EnvironmentObject var model: ArgusViewModel
    @State private var typed = ""
    @State private var showSettings = false
    @FocusState private var typing: Bool

    var body: some View {
        ZStack {
            background
            VStack(spacing: 10) {
                header
                modePicker
                if model.mode == .slt { signPanel }
                Spacer(minLength: 0)
                transcript
                inputRow
                controls
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
        }
        .sheet(isPresented: $showSettings) { SettingsView().environmentObject(model) }
    }

    // MARK: Background

    @ViewBuilder private var background: some View {
        if model.camera.isRunning {
            CameraPreview(session: model.camera.session).ignoresSafeArea()
            LinearGradient(colors: [.black.opacity(0.55), .clear, .black.opacity(0.75)],
                           startPoint: .top, endPoint: .bottom).ignoresSafeArea()
        } else {
            LinearGradient(colors: [Color(red: 0.03, green: 0.04, blue: 0.06), .black],
                           startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 8) {
                Image(systemName: model.mode == .screen ? "desktopcomputer" : "camera")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(argusGradient)
                Text(model.mode == .screen ? "Screen mode looks at your PC screen" : "Camera is off")
                    .font(.footnote).foregroundColor(.argusSubtext)
            }
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text("A.R.G.U.S.")
                    .font(.system(size: 22, weight: .heavy, design: .rounded))
                    .foregroundStyle(argusGradient)
                HStack(spacing: 6) {
                    Circle().fill(statusColor).frame(width: 8, height: 8)
                    Text(statusText).font(.caption).foregroundColor(.argusSubtext)
                }
            }
            Spacer()
            metric("VRAM", "\(model.metrics.vramUsed)/\(model.metrics.vramTotal)G")
            metric("GPU", model.metrics.temperature == "--" ? "--" : "\(model.metrics.temperature)°")
            metric("Reply", model.metrics.latency == "--" ? "--" : "\(model.metrics.latency)ms")
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill").font(.title3).foregroundColor(.white.opacity(0.85))
            }
            .accessibilityLabel("Settings")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color.glass))
        .padding(.top, 4)
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(value).font(.system(size: 12, weight: .semibold, design: .monospaced)).foregroundColor(.white)
            Text(label).font(.system(size: 9)).foregroundColor(.argusSubtext)
        }
    }

    private var statusColor: Color {
        switch model.connection {
        case .connected: return .argusGreen
        case .connecting: return .yellow
        case .disconnected: return .red
        }
    }

    private var statusText: String {
        switch model.connection {
        case .connected: return model.guiding ? "Connected • Guiding" : "Connected • \(model.mode.title)"
        case .connecting: return "Connecting…"
        case .disconnected: return "Offline • tap ⚙︎ to set the PC address"
        }
    }

    // MARK: Modes

    private var modePicker: some View {
        HStack(spacing: 8) {
            ForEach(ArgusMode.allCases) { m in
                if m != .slt || model.sltAvailable {
                    Button { model.selectMode(m) } label: {
                        HStack(spacing: 5) {
                            Image(systemName: m.symbol).font(.system(size: 13, weight: .semibold))
                            Text(m.title).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
                        }
                        .padding(.vertical, 8).frame(maxWidth: .infinity)
                        .foregroundColor(model.mode == m ? .black : .white)
                        .background(Capsule().fill(model.mode == m ? AnyShapeStyle(argusGradient) : AnyShapeStyle(Color.glass)))
                    }
                    .accessibilityLabel("\(m.title) mode")
                    .accessibilityAddTraits(model.mode == m ? .isSelected : [])
                }
            }
        }
    }

    // MARK: Sign language

    private var signPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(model.signStatus, systemImage: "hand.raised.fill")
                    .font(.caption.weight(.semibold)).foregroundColor(.argusTeal)
                Spacer()
                Menu {
                    ForEach(model.signLanguages) { l in
                        Button {
                            model.selectSignLanguage(l.code)
                        } label: {
                            Text(l.ready ? l.signLanguage : "\(l.signLanguage) (not ready)")
                        }
                        .disabled(!l.ready)
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(currentSignLabel).font(.caption.weight(.semibold))
                        Image(systemName: "chevron.down").font(.caption2)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Capsule().stroke(Color.argusTeal.opacity(0.6)))
                    .foregroundColor(.white)
                }
            }
            Text(model.signCaption.isEmpty ? "Sign in front of the camera, then pause." : model.signCaption)
                .font(.system(size: model.signCaption.isEmpty ? 15 : 24, weight: .bold))
                .foregroundColor(model.signCaption.isEmpty ? .argusSubtext : .white)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !model.signCaptionEnglish.isEmpty {
                Text(model.signCaptionEnglish).font(.system(size: 17, weight: .medium)).foregroundColor(.argusGreen)
            }
            HStack(spacing: 10) {
                pill("Translate now", "text.bubble") { model.signControl("translate") }
                pill("Clear", "xmark") { model.signControl("clear") }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color.glass))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.argusTeal.opacity(0.35)))
    }

    private var currentSignLabel: String {
        model.signLanguages.first { $0.code == model.signLanguage }?.signLanguage ?? model.signLanguage.uppercased()
    }

    private func pill(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.caption.weight(.semibold))
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(Color.white.opacity(0.12)))
                .foregroundColor(.white)
        }
    }

    // MARK: Transcript

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(model.messages) { m in
                        bubble(m).id(m.id)
                    }
                    if model.thinking {
                        HStack(spacing: 6) {
                            ProgressView().tint(.argusGreen).scaleEffect(0.8)
                            Text("Thinking…").font(.caption).foregroundColor(.argusSubtext)
                        }
                    }
                    if !model.speech.partial.isEmpty {
                        Text(model.speech.partial).font(.callout.italic()).foregroundColor(.white.opacity(0.6))
                            .id("partial")
                    }
                }
                .padding(12)
            }
            .frame(maxHeight: model.mode == .slt ? 170 : 300)
            .background(RoundedRectangle(cornerRadius: 18).fill(Color.glass))
            .onChange(of: model.messages) { msgs in
                if let last = msgs.last { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
            .onChange(of: model.speech.partial) { _ in proxy.scrollTo("partial", anchor: .bottom) }
        }
    }

    @ViewBuilder private func bubble(_ m: ChatMessage) -> some View {
        switch m.sender {
        case .system:
            Text(m.text).font(.caption).foregroundColor(.argusSubtext)
        case .you:
            HStack {
                Spacer(minLength: 40)
                Text(m.text).font(.callout).foregroundColor(.black)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color.argusGreen))
            }
        case .argus:
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(m.isWarning ? "⚠︎ A.R.G.U.S." : "A.R.G.U.S.")
                        .font(.caption2.weight(.bold)).foregroundColor(m.isWarning ? .argusWarn : .argusTeal)
                    Text(m.text.isEmpty ? "…" : m.text).font(.callout).foregroundColor(.white)
                        .textSelection(.enabled)
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 14).fill(m.isWarning ? Color.argusWarn.opacity(0.25) : Color.white.opacity(0.1)))
                Spacer(minLength: 40)
            }
        }
    }

    // MARK: Input

    private var inputRow: some View {
        HStack(spacing: 8) {
            Button { model.ocrMode.toggle() } label: {
                Text("OCR").font(.system(size: 12, weight: .bold))
                    .padding(.horizontal, 10).padding(.vertical, 9)
                    .background(Capsule().fill(model.ocrMode ? Color.argusGreen : Color.glass))
                    .foregroundColor(model.ocrMode ? .black : .white)
            }
            .accessibilityLabel(model.ocrMode ? "OCR on" : "OCR off")
            TextField("Ask ARGUS…", text: $typed)
                .focused($typing)
                .submitLabel(.send)
                .onSubmit(send)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Capsule().fill(Color.glass))
                .foregroundColor(.white)
            Button(action: send) {
                Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)).foregroundStyle(argusGradient)
            }
            .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityLabel("Send")
        }
    }

    private func send() {
        model.sendTyped(typed)
        typed = ""
        typing = false
    }

    // MARK: Controls

    private var controls: some View {
        HStack(alignment: .center) {
            control(model.camera.isRunning ? "video.fill" : "video.slash.fill", "Camera",
                    on: model.camera.isRunning) { model.toggleCamera() }
            Spacer()
            control("doc.text.viewfinder", "Read this", on: false) { model.readThis() }
            Spacer()
            Button { model.toggleVoice() } label: {
                ZStack {
                    Circle().fill(model.voiceOn ? AnyShapeStyle(argusGradient) : AnyShapeStyle(Color.glass))
                        .frame(width: 72, height: 72)
                        .shadow(color: model.voiceOn ? .argusGreen.opacity(0.6) : .clear, radius: 14)
                    Image(systemName: model.voiceOn ? "mic.fill" : "mic.slash.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundColor(model.voiceOn ? .black : .white)
                }
            }
            .accessibilityLabel(model.voiceOn ? "Voice on" : "Voice off")
            Spacer()
            control("figure.walk.motion", model.guiding ? "Stop guide" : "Guide", on: model.guiding) { model.toggleGuide() }
            Spacer()
            control("stop.fill", "Stop", on: false) { model.stopTalking() }
        }
        .padding(.horizontal, 6)
    }

    private func control(_ icon: String, _ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 19, weight: .semibold))
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(on ? Color.argusGreen.opacity(0.9) : Color.glass))
                    .foregroundColor(on ? .black : .white)
                Text(title).font(.system(size: 10, weight: .medium)).foregroundColor(.white.opacity(0.8))
            }
        }
        .accessibilityLabel(title)
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: ArgusViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("ws://192.168.1.10:8000/ws", text: $url)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("ARGUS server")
                } footer: {
                    Text("Start ARGUS on your PC, then enter the PC's IP address on the same Wi-Fi or hotspot. "
                         + "On Windows, run ipconfig and use the IPv4 address. Port 8000, path /ws.")
                }
                Section("GPU") {
                    LabeledContent("Card", value: model.metrics.gpuName.isEmpty ? "--" : model.metrics.gpuName)
                    LabeledContent("Last reply latency", value: model.metrics.latency == "--" ? "--" : "\(model.metrics.latency) ms")
                }
                Section {
                    Button("Clear conversation", role: .destructive) { model.clearChat() }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") {
                        model.reconnect(to: normalized(url))
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .onAppear { url = model.serverURL }
        }
    }

    /// Accepts "192.168.1.10" or "192.168.1.10:8000" and fills in the rest.
    private func normalized(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.hasPrefix("ws://") && !s.hasPrefix("wss://") { s = "ws://" + s }
        let afterScheme = s.components(separatedBy: "://").last ?? s
        let hostPort = afterScheme.split(separator: "/").first.map(String.init) ?? afterScheme
        if !hostPort.contains(":") { s = s.replacingOccurrences(of: hostPort, with: hostPort + ":8000") }
        if !afterScheme.contains("/") { s += "/ws" }
        return s
    }
}
