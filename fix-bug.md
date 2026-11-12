## Konsole OS Release Updates (v1.0.0-35mos)

### 🚀 Key Features & Enhancements
- **Active Terminal Session Switcher:** Replaced persistent notification exit action with an interactive "SWITCH" control allowing live switching, creation, and graceful closing of active sessions.
- **Konsole Sudo & Device Lockdown (`lock`):**
  - `lock`: Activates device screen pinning / kiosk lockdown mode.
  - `lock --sudo`: Completely locks Konsole OS until user's custom sudo password is provided.
  - `lock --sudo-set <password>`: Sets custom password and generates emergency SHA-256 rescue hash.
  - `lock --sudo-reset <hash>`: Recovers access using rescue hash.
  - `lock --sudo-redo`: Factory resets Konsole OS configuration and storage files.
  - **True Kiosk Lockdown Mode:** Device Policy Manager integration (`KonsoleDeviceAdminReceiver`) for blocking notification shade, status bar, and home gestures via ADB Device Owner.
- **Kons Package Manager (`kons install <pkg>`):**
  - Support for `.deb`, `.apk`, and `.kos` (plugin) packages with automated downloading and local registration.
- **Intelligent Autocomplete & Shell Engine:**
  - 4-Tier Fish-style suggestion provider (History, File/Directories, Binaries/Commands/Aliases, System Variables).
  - Ghost command hints, dynamic typing color glow feedback (blue/green/red), and quick `[C` completion.

### 🐛 Bug Fixes
- Fixed `FileNotFoundException: ENOENT` during startup file generation (`variable.txt` and XML configs) by implementing proactive directory verification.
- Added Android 12+ immutable `PendingIntent` flags in notification service builders.
- Fully signed release APK builds (`app-release.apk`) using integrated keystore configuration.
