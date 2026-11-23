import SwiftUI

struct ClimateThemeEditorView: View {
    let existing: ClimateThemeModel?
    let onSave: (ClimateThemeModel) -> Void
    let onDelete: ((String) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var draft: ClimateThemeModel

    init(
        existing: ClimateThemeModel?,
        onSave: @escaping (ClimateThemeModel) -> Void,
        onDelete: ((String) -> Void)? = nil
    ) {
        self.existing = existing
        self.onSave = onSave
        self.onDelete = onDelete
        _draft = State(initialValue: existing ?? ClimateThemeModel())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Profile Identity") {
                    TextField("Profile Name (e.g. Driveway Cool 69°F)", text: $draft.name)
                }

                Section("Cabin Temperature & Blower") {
                    Toggle("Use Celsius (°C)", isOn: $draft.celsius)
                    TextField("Target Temp (e.g. 69, 78, 21.5, LO, HI)", text: $draft.driverTemp)
                    Stepper("Fan Speed: \(draft.fanSpeed)", value: $draft.fanSpeed, in: 1...8)
                    Toggle("A/C Compressor ON", isOn: $draft.acOn)
                    Toggle("Cabin Heater ON", isOn: $draft.heatOn)
                    Toggle("Heated Steering Wheel", isOn: $draft.wheelHeat)
                    Toggle("Defrost ON", isOn: $draft.frontDefrost)
                }

                Section("Front Seats (Tap Level)") {
                    seatPickerRow("Driver Seat Heat", selection: $draft.driverSeatHeat)
                    seatPickerRow("Driver Seat Cool", selection: $draft.driverSeatVent)
                    seatPickerRow("Passenger Seat Heat", selection: $draft.passengerSeatHeat)
                    seatPickerRow("Passenger Seat Cool", selection: $draft.passengerSeatVent)
                }

                if let existing = existing, let onDelete = onDelete {
                    Section {
                        Button(role: .destructive) {
                            onDelete(existing.id)
                            dismiss()
                        } label: {
                            Text("Delete AC Profile")
                        }
                    }
                }
            }
            .navigationTitle(existing == nil ? "New AC Profile" : "Edit AC Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        draft.passengerTemp = draft.driverTemp
                        draft.syncZones = true
                        draft.rearDefrost = draft.frontDefrost
                        draft.rearLeftSeatHeat = 0
                        draft.rearRightSeatHeat = 0
                        draft.accentColor = draft.heatOn ? 0xFFF97316 : 0xFF0EA5E9
                        onSave(draft)
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
        }
    }

    private func seatPickerRow(_ label: String, selection: Binding<Int>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Picker(label, selection: selection) {
                Text("Off").tag(0)
                Text("1").tag(1)
                Text("2").tag(2)
                Text("3").tag(3)
            }
            .pickerStyle(.segmented)
        }
        .padding(.vertical, 2)
    }
}
