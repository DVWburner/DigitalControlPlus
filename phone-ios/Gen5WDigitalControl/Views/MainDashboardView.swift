import SwiftUI

private struct QueuedActionIntent {
    let action: String
    let arg: String
    let theme: ClimateThemeModel?
    let label: String
}

@MainActor
struct MainDashboardView: View {
    @ObservedObject var vault: KeychainVault
    @StateObject private var bt: BleCarTransport
    @StateObject private var remote: RemoteRelayService

    @State private var selectedTab: Int = 0
    @State private var showKeyBackupSheet = false
    @State private var showCommandLogSheet = false
    @State private var showNewThemeEditor = false
    @State private var editingTheme: ClimateThemeModel? = nil

    @State private var pendingSafetyConfirm: QueuedActionIntent? = nil
    @State private var pendingOfflineWarn: QueuedActionIntent? = nil
    @State private var remoteUrlDraft: String = ""
    @State private var remoteTokenDraft: String = ""
    @State private var nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    private let timer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    init(vault: KeychainVault) {
        self.vault = vault
        _bt = StateObject(wrappedValue: BleCarTransport(vault: vault))
        _remote = StateObject(wrappedValue: RemoteRelayService(vault: vault))
    }

    private var controlsEnabled: Bool {
        if bt.isConnected { return true }
        return vault.isCarPaired && vault.isRemoteServerConfigured && remote.remoteFallbackEnabled
    }

    var body: some View {
        VStack(spacing: 0) {
            topConnectionHeader

            TabView(selection: $selectedTab) {
                homeTab
                    .tabItem {
                        Label("Home", systemImage: "sparkles")
                    }
                    .tag(0)

                climateTab
                    .tabItem {
                        Label("Climate", systemImage: "snowflake")
                    }
                    .tag(1)

                controlsTab
                    .tabItem {
                        Label("Controls", systemImage: "car.side.fill")
                    }
                    .tag(2)

                sensorsTab
                    .tabItem {
                        Label("Sensors", systemImage: "gauge.with.dots.needle.bottom.50percent")
                    }
                    .tag(3)

                profileTab
                    .tabItem {
                        Label("Profile", systemImage: "person.crop.circle")
                    }
                    .tag(4)
            }
            .tint(.cyan)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10).ignoresSafeArea())
        .sheet(isPresented: $showKeyBackupSheet) {
            KeyBackupSheetView(vault: vault, bt: bt)
        }
        .sheet(isPresented: $showCommandLogSheet) {
            CommandActivityLogSheet(vault: vault, remote: remote, nowMs: nowMs)
        }
        .sheet(isPresented: $showNewThemeEditor) {
            ClimateThemeEditorView(existing: nil) { saved in
                vault.upsertTheme(saved)
            }
        }
        .sheet(item: $editingTheme) { item in
            ClimateThemeEditorView(existing: item) { saved in
                vault.upsertTheme(saved)
            } onDelete: { id in
                vault.deleteTheme(id: id)
            }
        }
        .sheet(isPresented: Binding(
            get: { bt.pendingSas4Code != nil },
            set: { if !$0 { bt.cancelPairing() } }
        )) {
            if let code = bt.pendingSas4Code {
                SasCodeConfirmationDialogView(
                    code: code,
                    carName: bt.pendingCarName,
                    onConfirm: { bt.confirmSas4Code() },
                    onCancel: { bt.cancelPairing() }
                )
                .presentationDetents([.medium])
            }
        }
        .confirmationDialog(
            "Confirm Action",
            isPresented: Binding(
                get: { pendingSafetyConfirm != nil },
                set: { if !$0 { pendingSafetyConfirm = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pending = pendingSafetyConfirm {
                Button("Send '\(pending.label)'") {
                    let chosen = pending
                    pendingSafetyConfirm = nil
                    checkOfflineAndDispatch(chosen)
                }
            }
            Button("Cancel", role: .cancel) {
                pendingSafetyConfirm = nil
            }
        }
        .alert(
            "Car Not Available",
            isPresented: Binding(
                get: { pendingOfflineWarn != nil },
                set: { if !$0 { pendingOfflineWarn = nil } }
            )
        ) {
            Button("Queue Anyway") {
                if let intent = pendingOfflineWarn {
                    pendingOfflineWarn = nil
                    remote.enqueueCommand(action: intent.action, arg: intent.arg, theme: intent.theme, label: intent.label)
                }
            }
            Button("Cancel", role: .cancel) {
                pendingOfflineWarn = nil
            }
        } message: {
            Text(shortOfflineWarningText)
        }
        .onReceive(timer) { date in
            nowMs = Int64(date.timeIntervalSince1970 * 1000)
            vault.sweepExpiredLogItems(nowMs: nowMs)
        }
        .onAppear {
            remoteUrlDraft = vault.remoteServerUrl
            remoteTokenDraft = vault.remoteAccountToken
            if vault.isRemoteServerConfigured {
                remote.startPolling()
            }
        }
        .onChange(of: vault.remoteServerUrl) { newUrl in
            remoteUrlDraft = newUrl
            if newUrl.hasPrefix("https://") {
                remote.startPolling()
            } else {
                remote.stopPolling()
            }
        }
    }

    private var shortOfflineWarningText: String {
        let last = vault.lastCarRemoteCheckInMs
        if last <= 0 {
            return "Car hasn't checked in on Wi-Fi yet. It may miss this command. Queue anyway?"
        }
        return "Car offline on Wi-Fi (\(relativeAgoShort(last))). It may miss this command. Queue anyway?"
    }

    // MARK: - Persistent Top Connection Summary

    private var topConnectionHeader: some View {
        let wifiOnline = vault.isRemoteServerConfigured && remote.isCarWifiRecentlyOnline(nowMs: nowMs)
        let lastWifi = vault.lastCarRemoteCheckInMs

        return VStack(spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Digital Control+")
                        .font(.headline.bold())
                        .foregroundStyle(.white)
                    Text(vault.isCarPaired ? vault.pairedCarName : "Not paired yet")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                // When Bluetooth is connected, only show a tiny "(Wi-Fi ●)" pill for Wi-Fi status
                if bt.isConnected && vault.isRemoteServerConfigured {
                    let dotColor: Color = wifiOnline ? .green : (lastWifi > 0 ? .orange : .gray)
                    Button {
                        selectedTab = 4
                    } label: {
                        HStack(spacing: 5) {
                            Text("Wi-Fi")
                                .font(.caption2.bold())
                                .foregroundStyle(.white)
                            Circle()
                                .fill(dotColor)
                                .frame(width: 7, height: 7)
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(Color(red: 0.06, green: 0.10, blue: 0.18))
                        .overlay(Capsule().stroke(dotColor.opacity(0.75), lineWidth: 1))
                        .clipShape(Capsule())
                    }
                }
            }

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    if bt.isConnected {
                        Circle().fill(Color.green).frame(width: 9, height: 9)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Bluetooth Connected")
                                .font(.footnote.bold())
                                .foregroundStyle(.white)
                            Text("Direct encrypted link active")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    } else if !vault.isCarPaired {
                        Circle().fill(Color.orange).frame(width: 9, height: 9)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("No Car Paired")
                                .font(.footnote.bold())
                                .foregroundStyle(.white)
                            Text("Open Profile to pair over Bluetooth")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    } else if !vault.isRemoteServerConfigured {
                        Circle().fill(Color.orange).frame(width: 9, height: 9)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Looking for car BT signal...")
                                .font(.footnote.bold())
                                .foregroundStyle(.white)
                            Text("Move within range while car is awake or charging")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    } else {
                        let dotColor: Color = !remote.remoteFallbackEnabled ? .gray : (wifiOnline ? .cyan : .orange)
                        Circle().fill(dotColor).frame(width: 9, height: 9)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(
                                !remote.remoteFallbackEnabled
                                    ? "Looking for car BT signal..."
                                    : (wifiOnline ? "Car Available via Wi-Fi" : "Car Not Available on Wi-Fi")
                            )
                            .font(.footnote.bold())
                            .foregroundStyle(.white)

                            Text(
                                !remote.remoteFallbackEnabled
                                    ? "Wi-Fi queueing paused"
                                    : (lastWifi > 0 ? "Checked in \(relativeAgoShort(lastWifi)) · 5m queue" : "No check-in yet · 5m queue")
                            )
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            remote.remoteFallbackEnabled.toggle()
                        } label: {
                            Text(remote.remoteFallbackEnabled ? "Wi-Fi ON" : "Wi-Fi OFF")
                                .font(.caption2.bold())
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(remote.remoteFallbackEnabled ? Color.cyan.opacity(0.22) : Color.gray.opacity(0.2))
                                .clipShape(Capsule())
                        }
                        .tint(.cyan)
                    }
                }

                let feedback = activeFeedbackText
                if !feedback.isEmpty {
                    Text(feedback)
                        .font(.caption2.bold())
                        .foregroundStyle(.cyan)
                }
            }
            .padding(10)
            .background(Color(red: 0.06, green: 0.09, blue: 0.16))
            .overlay(
                RoundedRectangle(cornerRadius: 11)
                    .stroke(bt.isConnected ? Color.green.opacity(0.7) : Color(red: 0.15, green: 0.25, blue: 0.40), lineWidth: 1)
            )
            .cornerRadius(11)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    private var activeFeedbackText: String {
        if !bt.isConnected && vault.isRemoteServerConfigured && !remote.lastRemoteFeedback.isEmpty {
            return remote.lastRemoteFeedback
        }
        return bt.lastActionFeedback
    }

    // MARK: - Tab 0: Home (AC Profiles + Active Pending Remote Strip)

    private var homeTab: some View {
        let activePending = remote.pendingCommands.filter { $0.status == "QUEUED" && nowMs < $0.expiresAtMs }
        return ScrollView {
            VStack(spacing: 12) {
                if !bt.isConnected && !activePending.isEmpty {
                    SectionCard(title: "PENDING WI-FI COMMANDS") {
                        ForEach(activePending) { item in
                            let remSec = max(0, (item.expiresAtMs - nowMs) / 1000)
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(item.label) sent.")
                                        .font(.subheadline.bold())
                                        .foregroundStyle(.white)
                                    Text(String(format: "Waiting for car · %d:%02d left", remSec / 60, remSec % 60))
                                        .font(.caption2)
                                        .foregroundStyle(.cyan)
                                }
                                Spacer()
                                Button("Cancel", role: .destructive) {
                                    remote.cancelCommand(commandId: item.id)
                                }
                                .font(.caption.bold())
                                .buttonStyle(.borderedProminent)
                                .tint(.red)
                            }
                            .padding(9)
                            .background(Color(red: 0.04, green: 0.07, blue: 0.13))
                            .cornerRadius(8)
                        }
                    }
                }

                SectionCard(title: "AC PROFILES") {
                    Text("Tap a preset to edit, or tap Activate to start cabin conditioning.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    ForEach(vault.climateThemes) { theme in
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(theme.name)
                                    .font(.subheadline.bold())
                                    .foregroundStyle(.white)
                                Text(theme.summaryLine)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                editingTheme = theme
                            }

                            Spacer()

                            Button {
                                requestAction(
                                    action: "apply_climate_theme",
                                    arg: "",
                                    theme: theme,
                                    label: theme.name,
                                    confirmSafety: false
                                )
                            } label: {
                                Text("Activate")
                                    .font(.caption.bold())
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 7)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.green)
                            .disabled(!controlsEnabled)
                            .opacity(controlsEnabled ? 1.0 : 0.38)
                        }
                        .padding(11)
                        .background(Color(red: 0.04, green: 0.07, blue: 0.13))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(theme.heatOn ? Color.orange.opacity(0.65) : Color.cyan.opacity(0.55), lineWidth: 1)
                        )
                        .cornerRadius(10)
                    }

                    Button {
                        showNewThemeEditor = true
                    } label: {
                        Label("New AC Profile", systemImage: "plus.circle.fill")
                            .font(.footnote.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                    .tint(.cyan)
                }
            }
            .padding(16)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10))
    }

    // MARK: - Tab 1: Climate (Current AC Sensor + Cabin Controls + Direct Seat Level Choosers)

    private var climateTab: some View {
        let s = vault.cachedSnapshot
        let unit = s.isCelsius ? "°C" : "°F"
        let drvTemp = s.raw("climate.driver_temp")
        let tempText = drvTemp == "--" ? "--°" : ((drvTemp == "LO" || drvTemp == "HI") ? drvTemp : "\(drvTemp)\(unit)")
        let hvacOn = s.flag("climate.on")

        return ScrollView {
            VStack(spacing: 12) {
                SectionCard(title: "CURRENT AC STATUS") {
                    HStack(spacing: 14) {
                        Text(tempText)
                            .font(.system(size: 32, weight: .heavy, design: .rounded))
                            .foregroundStyle(.cyan)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.raw("climate.on") == "--" ? "HVAC --" : (hvacOn ? "CLIMATE ON" : "CLIMATE OFF"))
                                .font(.subheadline.bold())
                                .foregroundStyle(hvacOn ? .green : .secondary)
                            Text("Fan \(s.raw("climate.fan_speed")) · Vent: \(s.ventModeFormatted)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }

                    Text("A/C: \(onOffText(s, "climate.ac")) · Heat: \(onOffText(s, "climate.heat")) · Wheel: \(onOffText(s, "wheel_heat")) · Defrost: \((s.flag("climate.front_defrost") || s.flag("defogger")) ? "ON" : "OFF")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(red: 0.04, green: 0.07, blue: 0.13))
                        .cornerRadius(8)
                }

                VStack(spacing: 12) {
                    SectionCard(title: "CABIN CLIMATE") {
                        HStack(spacing: 8) {
                            ctrlButton("Climate ON", action: "climate_on", tint: .cyan)
                            ctrlButton("AUTO", action: "climate_auto_on", tint: .green)
                            ctrlButton("Climate OFF", action: "climate_off", tint: .gray)
                        }
                        HStack(spacing: 8) {
                            ctrlButton("Temp −", action: "climate_driver_temp_down", tint: .blue)
                            ctrlButton("Temp +", action: "climate_driver_temp_up", tint: .orange)
                            ctrlButton("Fan −", action: "climate_fan_down", tint: .indigo)
                            ctrlButton("Fan +", action: "climate_fan_up", tint: .cyan)
                        }
                        HStack(spacing: 8) {
                            ctrlButton("Front Defrost", action: "climate_front_defrost_toggle", tint: .orange)
                            ctrlButton("Rear Defog", action: "rear_defrost_toggle", tint: .orange)
                        }
                    }

                    SectionCard(title: "STEERING WHEEL & FRONT SEATS") {
                        wheelHeatRow(currentState: s.raw("wheel_heat") == "--" ? -1 : (s.flag("wheel_heat") ? 1 : 0))
                        seatLevelRow(
                            title: "Driver Heat",
                            action: "seat_heat_driver_set",
                            currentLevel: s.intVal("seat_heat.driver", fallback: -1),
                            activeTint: .red
                        )
                        seatLevelRow(
                            title: "Driver Cool",
                            action: "seat_vent_driver_set",
                            currentLevel: s.intVal("seat_vent.driver", fallback: -1),
                            activeTint: .cyan
                        )
                        seatLevelRow(
                            title: "Passenger Heat",
                            action: "seat_heat_passenger_set",
                            currentLevel: s.intVal("seat_heat.passenger", fallback: -1),
                            activeTint: .red
                        )
                        seatLevelRow(
                            title: "Passenger Cool",
                            action: "seat_vent_passenger_set",
                            currentLevel: s.intVal("seat_vent.passenger", fallback: -1),
                            activeTint: .cyan
                        )
                    }
                }
                .opacity(controlsEnabled ? 1.0 : 0.40)
                .disabled(!controlsEnabled)
            }
            .padding(16)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10))
    }

    private func wheelHeatRow(currentState: Int) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("Steering Wheel Heat")
                .font(.caption.bold())
                .foregroundStyle(.white)
            HStack(spacing: 8) {
                Button {
                    requestAction(action: "wheel_heat_off", arg: "", theme: nil, label: "Steering Wheel Heat Off", confirmSafety: false)
                } label: {
                    Text("Off")
                        .font(.caption.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                }
                .buttonStyle(.borderedProminent)
                .tint(currentState == 0 ? .gray : Color(red: 0.12, green: 0.18, blue: 0.28))

                Button {
                    requestAction(action: "wheel_heat_on", arg: "", theme: nil, label: "Steering Wheel Heat On", confirmSafety: false)
                } label: {
                    Text("On")
                        .font(.caption.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                }
                .buttonStyle(.borderedProminent)
                .tint(currentState == 1 ? .red : Color(red: 0.12, green: 0.18, blue: 0.28))
            }
        }
    }

    private func seatLevelRow(title: String, action: String, currentLevel: Int, activeTint: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption.bold())
                .foregroundStyle(.white)
            HStack(spacing: 6) {
                ForEach(0..<4, id: \.self) { lvl in
                    let labelTxt = lvl == 0 ? "Off" : "\(lvl)"
                    let isSelected = (currentLevel == lvl)
                    Button {
                        requestAction(
                            action: action,
                            arg: "\(lvl)",
                            theme: nil,
                            label: "\(title) \(lvl == 0 ? "Off" : "Lvl \(lvl)")",
                            confirmSafety: false
                        )
                    } label: {
                        Text(labelTxt)
                            .font(.caption.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(isSelected ? activeTint : Color(red: 0.12, green: 0.18, blue: 0.28))
                }
            }
        }
    }

    // MARK: - Tab 2: Controls (Doors, Hatch Open/Close, Windows, Charge Port)

    private var controlsTab: some View {
        ScrollView {
            VStack(spacing: 12) {
                SectionCard(title: "DOORS & HATCH") {
                    HStack(spacing: 8) {
                        ctrlButton("Lock Doors", action: "door_lock", tint: .green)
                        ctrlButton("Unlock Doors", action: "door_unlock", tint: .orange, confirm: true)
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Open Hatch", action: "trunk_open", tint: .cyan, confirm: true)
                        ctrlButton("Close Hatch", action: "trunk_close", tint: .indigo, confirm: true)
                    }
                }

                SectionCard(title: "WINDOWS") {
                    HStack(spacing: 8) {
                        ctrlButton("Vent 25%", action: "window_all_vent", tint: .cyan)
                        ctrlButton("All 50%", action: "window_all_half", tint: .blue)
                        ctrlButton("All Up", action: "window_all_close", tint: .green)
                        ctrlButton("All Down", action: "window_all_open", tint: .orange, confirm: true)
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Drv Down", action: "window_fl_open", tint: .blue)
                        ctrlButton("Drv Up", action: "window_fl_close", tint: .indigo)
                        ctrlButton("Pass Down", action: "window_fr_open", tint: .blue)
                        ctrlButton("Pass Up", action: "window_fr_close", tint: .indigo)
                    }
                }

                SectionCard(title: "CHARGE PORT & BATTERY PRECONDITIONING") {
                    let s = vault.cachedSnapshot
                    HStack(spacing: 8) {
                        StatPill(
                            label: "Preconditioning",
                            value: s.preconditioningStatusFormatted,
                            valueColor: s.isPreconditioningActive ? .green : (s.isPreconditioningPending ? .orange : .white)
                        )
                        StatPill(
                            label: "HV Battery Temp",
                            value: s.batteryTempFormatted(preferFahrenheit: !s.isCelsius)
                        )
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Open Charge Port", action: "charge_door_open", tint: .teal)
                        ctrlButton("Close Charge Port", action: "charge_door_close", tint: .indigo)
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Toggle Battery Preconditioning", action: "precondition_toggle", tint: .yellow)
                    }
                }

                SectionCard(title: "UTILITY & BLUELINK CLIMATE (IGN-OFF)") {
                    let s = vault.cachedSnapshot
                    Text("Home AC Profiles & Climate tab set target temp (62–82°F), defrost, wheel & seats when Ign is off (E-GMP locks fan to Auto 1).")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    HStack(spacing: 8) {
                        StatPill(
                            label: "Utility Mode",
                            value: s.utilityModeStatusFormatted,
                            valueColor: s.isUtilityModeActive ? .green : .white
                        )
                        StatPill(
                            label: "VCU Powertrain",
                            value: s.evReadyStatusFormatted,
                            valueColor: s.intVal("hv.ready", fallback: -1) == 1 ? .green : .secondary
                        )
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Engage Utility Mode", action: "utility_mode_on", tint: .purple)
                    }
                    HStack(spacing: 8) {
                        ctrlButton("Bluelink Climate ON", action: "tmu_fatc_on", tint: .cyan)
                        ctrlButton("Bluelink Climate OFF", action: "tmu_fatc_off", tint: .gray)
                    }
                }
            }
            .opacity(controlsEnabled ? 1.0 : 0.40)
            .disabled(!controlsEnabled)
            .padding(16)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10))
    }

    // MARK: - Tab 3: Sensors (Confirmation Time, Locks, Tires, Hatch, EV Battery)

    private var sensorsTab: some View {
        let s = vault.cachedSnapshot
        let confirmMs = max(s.updatedMs, vault.lastCarRemoteCheckInMs)
        let soc = s.raw("hv.soc")
        let pwr = s.raw("charging.power_kw")
        let anyLowTire = s.isTireLow("tyre.fl_status") || s.isTireLow("tyre.fr_status")
            || s.isTireLow("tyre.rl_status") || s.isTireLow("tyre.rr_status")

        return ScrollView {
            VStack(spacing: 12) {
                SectionCard(title: "LAST VEHICLE CONFIRMATION") {
                    if confirmMs <= 0 {
                        Text("No vehicle confirmation yet")
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        Text("Waiting for Bluetooth or Wi-Fi telemetry")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        let d = Date(timeIntervalSince1970: Double(confirmMs) / 1000.0)
                        Text(DateFormatter.localizedString(from: d, dateStyle: .medium, timeStyle: .medium))
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        Text(bt.isConnected ? "Live via Bluetooth" : "Confirmed \(relativeAgoShort(confirmMs))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                SectionCard(title: "LOCKS, DOORS & HATCH") {
                    HStack(spacing: 8) {
                        StatPill(
                            label: "Door Locks",
                            value: s.raw("door.lock_state") == "--" ? "--" : (s.flag("door.lock_state") ? "LOCKED" : "UNLOCKED"),
                            valueColor: s.raw("door.lock_state") == "--" ? .secondary : (s.flag("door.lock_state") ? .green : .orange)
                        )
                        StatPill(
                            label: "Rear Hatch",
                            value: s.raw("trunk.open") == "--" ? "--" : (s.flag("trunk.open") ? "OPEN" : "CLOSED"),
                            valueColor: s.raw("trunk.open") == "--" ? .secondary : (s.flag("trunk.open") ? .orange : .green)
                        )
                    }
                    HStack(spacing: 8) {
                        StatPill(label: "Doors Ajar", value: s.doorsSummary)
                        StatPill(
                            label: "Charge Door",
                            value: s.raw("charge_door.open") == "--" ? "--" : (s.flag("charge_door.open") ? "OPEN" : "CLOSED")
                        )
                    }
                }

                SectionCard(title: "TIRE STATUS (TPMS)") {
                    Text(s.tiresSummary)
                        .font(.footnote.bold())
                        .foregroundStyle(anyLowTire ? Color.red : Color.green)

                    HStack(spacing: 8) {
                        tirePill("Front Left", key: "tyre.fl_status", snap: s)
                        tirePill("Front Right", key: "tyre.fr_status", snap: s)
                    }
                    HStack(spacing: 8) {
                        tirePill("Rear Left", key: "tyre.rl_status", snap: s)
                        tirePill("Rear Right", key: "tyre.rr_status", snap: s)
                    }
                }

                SectionCard(title: "EV BATTERY & CHARGING") {
                    HStack(spacing: 14) {
                        Text(soc == "--" ? "--%" : "\(soc)%")
                            .font(.system(size: 32, weight: .heavy, design: .rounded))
                            .foregroundStyle(.cyan)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(
                                s.isCharging
                                    ? "Charging\(pwr == "--" ? "" : " · \(pwr) kW") (\(s.chargeRemainingFormatted))"
                                    : (soc == "--" ? "Waiting for telemetry" : "Not Charging")
                            )
                            .font(.subheadline.bold())
                            .foregroundStyle(s.isCharging ? .green : .white)

                            Text("Est. Range: \(s.rangeFormatted(useMiles: vault.useMiles))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    HStack(spacing: 8) {
                        StatPill(
                            label: "Preconditioning",
                            value: s.preconditioningStatusFormatted,
                            valueColor: s.isPreconditioningActive ? .green : (s.isPreconditioningPending ? .orange : .white)
                        )
                        StatPill(
                            label: "HV Battery Temp",
                            value: s.batteryTempFormatted(preferFahrenheit: !s.isCelsius)
                        )
                    }
                    HStack(spacing: 8) {
                        StatPill(label: "12V Aux", value: s.raw("battery12v.soc") == "--" ? "--" : "\(s.raw("battery12v.soc"))%")
                        StatPill(label: "Outside Temp", value: s.ambientTempFormatted)
                    }
                }
            }
            .padding(16)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10))
    }

    private func tirePill(_ label: String, key: String, snap: VehicleTelemetrySnapshot) -> some View {
        let st = snap.singleTireStatus(key)
        let col: Color = st == "LOW" ? .red : (st == "OK" ? .green : .secondary)
        return StatPill(label: label, value: st, valueColor: col)
    }

    // MARK: - Tab 4: Profile (Connection Details, Command Log, Server & Key Management)

    private var profileTab: some View {
        let wifiOnline = vault.isRemoteServerConfigured && remote.isCarWifiRecentlyOnline(nowMs: nowMs)
        let lastWifi = vault.lastCarRemoteCheckInMs

        return ScrollView {
            VStack(spacing: 12) {
                SectionCard(title: "CONNECTION & TRUST DETAILS") {
                    Text(vault.isCarPaired ? "Paired Vehicle: \(vault.pairedCarName)" : "No Vehicle Paired Yet")
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)

                    Text("Bluetooth: \(bt.isConnected ? "Connected (Encrypted)" : bt.statusMessage)")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if !vault.isRemoteServerConfigured {
                        Text("Wi-Fi Server: Disabled (Bluetooth-only mode)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        let host = vault.remoteServerUrl.replacingOccurrences(of: "https://", with: "")
                        let authLabel = vault.remoteAccountToken.isEmpty ? "Key Auth" : "Token Signed-In"
                        let seenLabel = lastWifi > 0 ? "checked in \(relativeAgoShort(lastWifi))" : "never checked in"
                        Text("Wi-Fi Server: \(host) (\(wifiOnline ? "Online" : "Offline") · \(seenLabel) · \(authLabel))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Text("Phone Key: \(vault.keyFingerprintShort)\(vault.isCarPaired ? " · Car ID: \(vault.pairedCarId)" : "")")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }

                SectionCard(title: "COMMAND ACTIVITY LOG") {
                    Text(vault.commandLog.first.map { "Latest: \($0.friendlyHeadline)" } ?? "Track every command sent, executed, or timed out.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Button {
                        showCommandLogSheet = true
                    } label: {
                        Label(
                            vault.commandLog.isEmpty ? "View Command Log" : "View Command Log (\(vault.commandLog.count))",
                            systemImage: "list.bullet.rectangle.portrait"
                        )
                        .font(.footnote.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.cyan.opacity(0.35))
                }

                SectionCard(title: "REMOTE WI-FI SERVER") {
                    Text("https:// is assumed automatically (leave blank for Bluetooth-only):")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    TextField("your-server.example.com", text: $remoteUrlDraft)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(10)
                        .background(Color(red: 0.04, green: 0.07, blue: 0.13))
                        .cornerRadius(8)

                    SecureField("Optional Account / Bearer Token", text: $remoteTokenDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(9)
                        .background(Color(red: 0.04, green: 0.07, blue: 0.13))
                        .cornerRadius(8)

                    Toggle("Allow queueing over Wi-Fi when BT is out of range", isOn: $remote.remoteFallbackEnabled)
                        .font(.caption)
                        .tint(.cyan)

                    Button {
                        let clean = remoteUrlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        let tok = clean.isEmpty ? "" : remoteTokenDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        vault.setRemoteSettings(url: clean, token: tok)
                        if bt.isConnected {
                            bt.syncRemoteServerToCar(url: vault.remoteServerUrl, token: vault.remoteAccountToken)
                        }
                    } label: {
                        Text(bt.isConnected ? "Save & Sync Server to Car" : "Save Server Settings")
                            .font(.footnote.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)

                    if vault.isRemoteServerConfigured {
                        Button("Switch to Bluetooth-Only Mode", role: .destructive) {
                            remoteUrlDraft = ""
                            remoteTokenDraft = ""
                            vault.setRemoteSettings(url: "", token: "")
                            if bt.isConnected {
                                bt.syncRemoteServerToCar(url: "", token: "")
                            }
                        }
                        .font(.caption.bold())
                    }
                }

                SectionCard(title: "VEHICLE PAIRING & SECURITY KEY") {
                    Toggle("Display range in Miles (mi)", isOn: $vault.useMiles)
                        .font(.caption)
                        .tint(.cyan)
                        .onChange(of: vault.useMiles) { newVal in
                            UserDefaults.standard.set(newVal, forKey: "use_miles")
                        }

                    Button {
                        bt.requestManualPairingMode()
                    } label: {
                        Label("Start Bluetooth Pairing Handshake", systemImage: "antenna.radiowaves.left.and.right")
                            .font(.footnote.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)

                    Button {
                        showKeyBackupSheet = true
                    } label: {
                        Label("Security Key Backup & Restore", systemImage: "key.icloud.fill")
                            .font(.footnote.bold())
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                    .tint(.cyan)

                    if vault.isCarPaired {
                        Button(role: .destructive) {
                            vault.clearPairedCar()
                            bt.cancelPairing()
                        } label: {
                            Text("Forget Paired Car")
                                .font(.caption.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 5)
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
            .padding(16)
        }
        .background(Color(red: 0.03, green: 0.05, blue: 0.10))
    }

    // MARK: - Helpers & Action Routing

    private func ctrlButton(_ label: String, action: String, arg: String = "", tint: Color, confirm: Bool = false) -> some View {
        Button {
            requestAction(action: action, arg: arg, theme: nil, label: label, confirmSafety: confirm)
        } label: {
            Text(label)
                .font(.caption.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint.opacity(0.82))
    }

    private func requestAction(action: String, arg: String, theme: ClimateThemeModel?, label: String, confirmSafety: Bool) {
        let intent = QueuedActionIntent(action: action, arg: arg, theme: theme, label: label)
        if confirmSafety {
            pendingSafetyConfirm = intent
        } else {
            checkOfflineAndDispatch(intent)
        }
    }

    private func checkOfflineAndDispatch(_ intent: QueuedActionIntent) {
        if bt.isConnected {
            bt.sendCommand(action: intent.action, arg: intent.arg, theme: intent.theme, label: intent.label)
            return
        }
        if vault.isRemoteServerConfigured && remote.remoteFallbackEnabled {
            if !remote.isCarWifiRecentlyOnline(nowMs: nowMs) {
                pendingOfflineWarn = intent
            } else {
                remote.enqueueCommand(action: intent.action, arg: intent.arg, theme: intent.theme, label: intent.label)
            }
        }
    }

    private func onOffText(_ s: VehicleTelemetrySnapshot, _ key: String) -> String {
        s.raw(key) == "--" ? "--" : (s.flag(key) ? "ON" : "OFF")
    }

    private func relativeAgoShort(_ pastMs: Int64) -> String {
        guard pastMs > 0 else { return "never" }
        let sec = max(0, (nowMs - pastMs) / 1000)
        if sec < 5 { return "just now" }
        if sec < 60 { return "\(sec)s ago" }
        let min = sec / 60
        if min < 60 { return "\(min)m ago" }
        let hr = min / 60
        if hr < 24 { return "\(hr)h ago" }
        return "\(hr / 24)d ago"
    }
}

private struct CommandActivityLogSheet: View {
    @ObservedObject var vault: KeychainVault
    @ObservedObject var remote: RemoteRelayService
    let nowMs: Int64
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if vault.commandLog.isEmpty {
                    Text("No commands sent yet. Sent, executed, and timed-out commands will appear here.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(vault.commandLog) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(iconPrefix(for: item.state)) \(item.friendlyHeadline)")
                                    .font(.subheadline.bold())
                                    .foregroundStyle(color(for: item.state))
                                Text(item.subline(nowMs: nowMs))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if item.isPendingRemote(nowMs: nowMs) {
                                Button("Cancel", role: .destructive) {
                                    remote.cancelCommand(commandId: item.id)
                                }
                                .font(.caption.bold())
                                .buttonStyle(.borderedProminent)
                                .tint(.red)
                            }
                        }
                        .padding(.vertical, 3)
                    }
                }
            }
            .navigationTitle("Command Log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !vault.commandLog.isEmpty {
                        Button("Clear", role: .destructive) {
                            vault.clearCommandLog()
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func iconPrefix(for state: String) -> String {
        switch state {
        case "EXECUTED": return "✓"
        case "TIMED_OUT": return "⏱"
        case "FAILED": return "⚠"
        case "CANCELED": return "✕"
        default: return "↑"
        }
    }

    private func color(for state: String) -> Color {
        switch state {
        case "EXECUTED": return .green
        case "TIMED_OUT": return .orange
        case "FAILED": return .red
        case "CANCELED": return .secondary
        default: return .cyan
        }
    }
}

private struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.caption2.bold())
                .foregroundStyle(.cyan)
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0.06, green: 0.09, blue: 0.16))
        .overlay(
            RoundedRectangle(cornerRadius: 13)
                .stroke(Color(red: 0.12, green: 0.18, blue: 0.28), lineWidth: 1)
        )
        .cornerRadius(13)
    }
}

private struct StatPill: View {
    let label: String
    let value: String
    var valueColor: Color = .white

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.footnote.bold())
                .foregroundStyle(valueColor)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0.04, green: 0.07, blue: 0.13))
        .cornerRadius(9)
    }
}
