import SwiftUI
import UniformTypeIdentifiers

struct KeyBackupFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data, .json, .plainText] }

    var text: String

    init(text: String = "") {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let decoded = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = decoded
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct KeyBackupSheetView: View {
    @ObservedObject var vault: KeychainVault
    @ObservedObject var bt: BleCarTransport
    @Environment(\.dismiss) private var dismiss

    @State private var exportPass1: String = ""
    @State private var exportPass2: String = ""
    @State private var importPass: String = ""

    @State private var exportDoc: KeyBackupFileDocument? = nil
    @State private var showFileExporter: Bool = false
    @State private var showFileImporter: Bool = false
    @State private var pendingImportedText: String? = nil
    @State private var showRestorePassAlert: Bool = false
    @State private var statusBanner: String = ""
    @State private var statusIsError: Bool = false

    private var suggestedFilename: String {
        let df = DateFormatter()
        df.dateFormat = "yyyyMMdd"
        return "gen5w-security-key-\(df.string(from: Date())).g5wkey"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Active iPhone Security Key") {
                    LabeledContent("Phone ID", value: vault.phoneId)
                        .font(.caption.monospaced())
                    LabeledContent("Key Fingerprint", value: vault.keyFingerprintShort)
                        .font(.subheadline.monospaced().bold())
                    LabeledContent(
                        "Paired Car",
                        value: vault.isCarPaired ? "\(vault.pairedCarName) (\(vault.pairedCarId))" : "None yet"
                    )
                    .font(.caption)
                }

                Section {
                    Toggle(
                        "Include Key in iPhone & iCloud Backup",
                        isOn: Binding(
                            get: { vault.appleBackupEnabled },
                            set: { vault.setAppleBackupEnabled($0) }
                        )
                    )
                } header: {
                    Text("Apple iPhone & iCloud Backup")
                } footer: {
                    Text("Stores your EC P-256 car security key in the iOS Keychain using AfterFirstUnlock accessibility so encrypted Apple iPhone Backups, iCloud Backups, and Quick Start device transfers automatically back up and restore your key.")
                }

                Section {
                    SecureField("Create Backup Passphrase (6+ chars)", text: $exportPass1)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Confirm Backup Passphrase", text: $exportPass2)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button {
                        prepareEncryptedExport()
                    } label: {
                        Label("Save Encrypted Key Backup to Files...", systemImage: "square.and.arrow.down.on.square.fill")
                            .fontWeight(.semibold)
                    }
                    .tint(.green)
                } header: {
                    Text("Export Local Encrypted File (.g5wkey)")
                } footer: {
                    Text("Encrypts your security key, paired car trust grant, and Climate Themes with PBKDF2-HMAC-SHA256 + AES-256 so you can save a local copy to 'On My iPhone' or iCloud Drive. Compatible with both iPhone and Android.")
                }

                Section("Restore Key from Backup") {
                    Button {
                        showFileImporter = true
                    } label: {
                        Label("Choose .g5wkey Backup File from Phone...", systemImage: "folder.badge.person.crop")
                            .fontWeight(.semibold)
                    }

                    if vault.hasLocalBackupSnapshot {
                        let dateStr = vault.localBackupSnapshotDate.map {
                            DateFormatter.localizedString(from: $0, dateStyle: .medium, timeStyle: .short)
                        } ?? "Saved"
                        Button {
                            do {
                                pendingImportedText = try vault.readLocalBackupSnapshot()
                                importPass = ""
                                showRestorePassAlert = true
                            } catch {
                                setStatus(error.localizedDescription, isError: true)
                            }
                        } label: {
                            Label("Restore Quick Local Snapshot (\(dateStr))", systemImage: "clock.arrow.circlepath")
                        }
                    }
                }

                if !statusBanner.isEmpty {
                    Section {
                        Text(statusBanner)
                            .font(.footnote.bold())
                            .foregroundStyle(statusIsError ? Color.red : Color.green)
                    }
                }
            }
            .navigationTitle("Key Backup & Restore")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .fileExporter(
                isPresented: $showFileExporter,
                document: exportDoc,
                contentType: .data,
                defaultFilename: suggestedFilename
            ) { result in
                switch result {
                case .success:
                    exportPass1 = ""
                    exportPass2 = ""
                    setStatus("Encrypted security key backup saved (\(vault.keyFingerprintShort)).", isError: false)
                case .failure(let err):
                    setStatus("Local snapshot saved; file export canceled or failed: \(err.localizedDescription)", isError: true)
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.data, .json, .item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let data = try Data(contentsOf: url)
                        guard data.count <= 512 * 1024,
                              let text = String(data: data, encoding: .utf8) else {
                            throw NSError(domain: "KeyBackup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid backup file encoding"])
                        }
                        pendingImportedText = text
                        importPass = ""
                        showRestorePassAlert = true
                    } catch {
                        setStatus("Could not read file: \(error.localizedDescription)", isError: true)
                    }
                case .failure(let err):
                    setStatus("Import canceled: \(err.localizedDescription)", isError: true)
                }
            }
            .alert("Decrypt Security Key Backup", isPresented: $showRestorePassAlert) {
                SecureField("Backup Passphrase", text: $importPass)
                Button("Restore", role: .none) {
                    performRestore()
                }
                Button("Cancel", role: .cancel) {
                    pendingImportedText = nil
                    importPass = ""
                }
            } message: {
                Text("Enter the passphrase used when creating this backup. Restoring replaces this app's current security key and paired car record.")
            }
        }
    }

    private func prepareEncryptedExport() {
        guard exportPass1.count >= 6 else {
            setStatus("Passphrase must be at least 6 characters.", isError: true)
            return
        }
        guard exportPass1 == exportPass2 else {
            setStatus("Passphrases did not match.", isError: true)
            return
        }
        let pass = exportPass1
        Task.detached(priority: .userInitiated) {
            do {
                let json = try await MainActor.run { try vault.exportEncryptedBackupJson(passphrase: pass) }
                await MainActor.run {
                    exportDoc = KeyBackupFileDocument(text: json)
                    showFileExporter = true
                    setStatus("Local snapshot saved — choose where to save your .g5wkey file.", isError: false)
                }
            } catch {
                await MainActor.run {
                    setStatus("Backup failed: \(error.localizedDescription)", isError: true)
                }
            }
        }
    }

    private func performRestore() {
        guard let encrypted = pendingImportedText, !importPass.isEmpty else {
            setStatus("Passphrase is required to decrypt backup.", isError: true)
            return
        }
        let pass = importPass
        pendingImportedText = nil
        importPass = ""
        do {
            try vault.importEncryptedBackupJson(encrypted, passphrase: pass)
            bt.restartAfterKeyRestore()
            setStatus("Restored security key (\(vault.keyFingerprintShort)) & vehicle pairing!", isError: false)
        } catch {
            setStatus("Restore failed: \(error.localizedDescription)", isError: true)
        }
    }

    private func setStatus(_ msg: String, isError: Bool) {
        statusBanner = msg
        statusIsError = isError
    }
}
