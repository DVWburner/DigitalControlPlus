import SwiftUI

struct PairingSheetView: View {
    @ObservedObject var vault: KeychainVault
    @ObservedObject var bt: BleCarTransport
    @Environment(\.dismiss) private var dismiss

    @State private var remoteUrlDraft: String = ""
    @State private var remoteTokenDraft: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Bluetooth Low Energy Pairing") {
                    Text("1. Open 'Digital Control+' on your car's widescreen display and unlock Pairing Mode with your PIN.\n2. Tap 'Start iPhone Pairing Handshake' below — both screens will show a matching 4-character confirmation code.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    Button {
                        bt.requestManualPairingMode()
                        dismiss()
                    } label: {
                        Label("Start iPhone Pairing Handshake", systemImage: "antenna.radiowaves.left.and.right")
                            .fontWeight(.semibold)
                    }
                }

                Section("Security Key Backup & Restore") {
                    NavigationLink {
                        KeyBackupSheetView(vault: vault, bt: bt)
                    } label: {
                        Label("Backup or Restore Security Key", systemImage: "key.icloud.fill")
                            .fontWeight(.semibold)
                    }
                    Text("Fingerprint: \(vault.keyFingerprintShort) · iPhone/iCloud Backup \(vault.appleBackupEnabled ? "ON" : "OFF")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Display Preferences") {
                    Toggle("Show Range in Miles (mi)", isOn: $vault.useMiles)
                        .onChange(of: vault.useMiles) { newVal in
                            UserDefaults.standard.set(newVal, forKey: "use_miles")
                        }
                }

                Section {
                    TextField("https://your-server.example.com", text: $remoteUrlDraft)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    SecureField("Optional Server / Account Bearer Token", text: $remoteTokenDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button(bt.isConnected ? "Save & Sync Server to Car" : "Save Remote Server") {
                        let clean = remoteUrlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        let tok = clean.isEmpty ? "" : remoteTokenDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        vault.setRemoteSettings(url: clean, token: tok)
                        if bt.isConnected {
                            bt.syncRemoteServerToCar(url: vault.remoteServerUrl, token: vault.remoteAccountToken)
                        }
                        dismiss()
                    }

                    if vault.isRemoteServerConfigured {
                        Button("Disable Remote Server (BT Only)", role: .destructive) {
                            vault.setRemoteSettings(url: "", token: "")
                            if bt.isConnected {
                                bt.syncRemoteServerToCar(url: "", token: "")
                            }
                            dismiss()
                        }
                    }
                } header: {
                    Text("Optional Self-Hosted Remote Server (HTTPS)")
                } footer: {
                    Text("Leave blank for Bluetooth-only mode, or specify your own self-hosted HTTPS car relay server.")
                }

                if vault.isCarPaired {
                    Section("Paired Vehicle (\(vault.pairedCarName))") {
                        Text("Car Relay ID: \(vault.pairedCarId)")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)

                        Button(role: .destructive) {
                            vault.clearPairedCar()
                            bt.cancelPairing()
                            dismiss()
                        } label: {
                            Text("Forget Paired Car")
                        }
                    }
                }
            }
            .navigationTitle("Vehicle & Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                remoteUrlDraft = vault.remoteServerUrl
                remoteTokenDraft = vault.remoteAccountToken
            }
        }
    }
}

struct SasCodeConfirmationDialogView: View {
    let code: String
    let carName: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Text("Verify Pairing Code")
                .font(.title3.bold())
                .foregroundStyle(.white)

            Text("Confirm this 4-character code matches your \(carName) head unit screen:")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.gray)

            Text(code.map(String.init).joined(separator: "   "))
                .font(.system(size: 36, weight: .heavy, design: .monospaced))
                .foregroundStyle(Color.green)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity)
                .background(Color(red: 0.02, green: 0.18, blue: 0.09))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.green, lineWidth: 2)
                )
                .cornerRadius(12)

            Text("Also tap 'Approve & Pair Phone' on the car screen.")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                Button(role: .cancel, action: onCancel) {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)

                Button(action: onConfirm) {
                    Text("Confirm Match")
                        .fontWeight(.bold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
            }
        }
        .padding(24)
        .background(Color(red: 0.06, green: 0.09, blue: 0.16))
    }
}
