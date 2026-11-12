## Konsole OS Release Updates (v1.0.0-35mos)

### 🚀 Key Features & Enhancements
- **Active Terminal Session Switcher:** Replaced persistent notification exit action with an interactive "SWITCH" control allowing live switching, creation, and graceful closing of active sessions.

# 1. Core Identity & Launcher Transformation
* **App Name:** Renamed the application to `Konsole OS` across all strings, layouts, and notifications.
* **Package Name Refactor:** Refactored the package name from `com.termux` to `com.huntech.konsoleos`. Ensure all hardcoded `com.termux` paths in `TermuxConstants` (`termux-shared` module) and JNI/bootstrap references are updated cleanly so bootstrap packages work without path collisions.
* **Android Launcher Integration:** 
  * Updated `TermuxActivity` (or the main entry activity) in `AndroidManifest.xml` to include `<category android:name="android.intent.category.HOME" />` and `<category android:name="android.intent.category.DEFAULT" />`.
  * Prevented the back button from exiting the app when set as the default launcher.
  * Requested necessary system permissions on startup (`READ_CONTACTS`, `CALL_PHONE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, `CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`, `CAMERA`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`).

---

# 2. Command Interception & Execution Pipeline
Redesigned the terminal input handling pipeline inside `TermuxTerminalSessionClient` / `TerminalView` before bytes are sent to the native PTY shell:
1. **Mode Check:** Check the current shell mode (`konsole` vs `termux`). If in `termux` mode, bypass custom interception (except for `kons switch konsole`).
2. **Variable & Shortcut Expansion:** Before parsing any command, expand `$konsole` to the absolute path of `/storage/emulated/0/Konsole`, and expand any custom or system variables defined in `variables.xml` (e.g., `$cpu_usage`, `$time`, `$my_var`).
3. **Alias Resolution:** Check if the command matches a user-defined alias.
4. **Custom Command Router:** Check if the input matches a built-in `Konsole OS` command, an installed `kons` package, or an installed Android `<app-name>`.
5. **Fallback:** If no custom command or app matches, pass the raw command string directly to the native Termux bash/zsh PTY session.

---

# 3. Custom Commands Specification
Implemented a modular `CommandDispatcher` in Java/Kotlin to handle the following commands natively via Android APIs:

### App Launching & System Control
* `<app-name>`: Fuzzy-match installed Android launchable apps by label. Launch the matching app via `PackageManager.getLaunchIntentForPackage()`.
* `bluetooth status|on|off|ls|connect <mac-address>`: Control Bluetooth adapter, scan/list paired & nearby devices (`ls`), and initiate connections.
* `wifi status|on|off|ls|connect <ssid>`: Control Wi-Fi, scan networks (`ls`), and connect to an SSID. If the network is not saved, prompt the user interactively in the terminal for the password before connecting.
* `music play|pause|stop|ls`: Control active media playback using Android `AudioManager` / `MediaSessionManager` key events, and list local audio files (`ls`).
* `call <number or contact name>`: Resolve contact name via `ContactsContract` if text is provided, then initiate a phone call (`Intent.ACTION_CALL`).
* `contact ls|search <query>`: List or search device contacts and print formatted names and phone numbers to the terminal.
* `volume <stream> <level>`: Set volume for streams (`voicecall=1`, `system=2`, `media=3`, `alarm=4`, `notification=5`, `ringer=6`). Also support ringer modes: `silent=00`, `vibrate=01`, `normal=11`.
* `calc <expression>`: Evaluate mathematical expressions natively and print the result.
* `flash on|off`: Toggle the device camera flashlight using `CameraManager.setTorchMode()`.
* `vibrate <pattern>`: Parse comma-separated milliseconds (e.g., `vibrate 0,300,500`) and trigger `Vibrator` / `VibrationEffect`.
* `beep <pattern>`: Parse comma-separated patterns using `ToneGenerator` where `0` = single beep, `1` = double beep, `-` = 1 second silence per dash, and `-N` (e.g., `-6`) = `N` seconds of silence (e.g., `beep 1,-6,0`).

### The `kons` Management Suite
* `kons apps`: Open a terminal-styled, keyboard-navigable GUI modal/overlay displaying all installed apps in a clean grid view.
* `kons set <variable-name=value>`: Save persistent user variables to `$konsole/customize/variables.xml` so they are accessible system-wide.
* `kons notify <title> <message>`: Dispatch a native Android system notification.
* `kons toast <message>`: Display an Android `Toast` message.
* `kons alias <name=alias-value>`: Save persistent command aliases to `$konsole/customize/aliases.xml`.
* `kons list`: List all installed custom `kons` packages.
* `kons install <pkg>` & `kons update <pkg>`: Fetch package metadata from `https://github.com/hunter-zen-RU/Konsole-packages/packages.json`, download the script/package, verify the `#!/bin/bash/kons` shebang, and install it to the Konsole bin directory.
* `kons version` & `kons about`: Display Konsole OS version, build info, and credits.
* `kons update`: Query the GitHub Releases API for `https://github.com/hunter-zen-RU/Konsole-OS`, detect the device's CPU architecture (`arm64-v8a`, `armeabi-v7a`, `x86_64`, or fallback to `universal`), download the latest APK, and trigger the Android package installer.
* `kons help`: Print a formatted, categorized manual of all Konsole OS commands.
* `kons switch <termux|konsole>`: Toggle between pure Termux mode and Konsole OS launcher mode.

### Additional Essential Launcher Commands
design and implement essential phone-control commands:
* `brightness <0-100|auto>`: Adjust screen brightness.
* `dnd on|off`: Toggle Do Not Disturb mode.
* `wallpaper <image-path>`: Set the system/launcher wallpaper.
* `lock`: Lock the device screen (via Device Admin or Accessibility Service).
* `battery`: Show detailed battery percentage, health, temperature, and charging status.
* `clear` / `home`: Clear terminal buffer and reset to the OS dashboard header.
* `uninstall <app-name>`: Trigger Android app uninstallation.
* `settings [wifi|bluetooth|apps|display]`: Open specific Android system settings screens.

---

# 4. Storage, XML Customization & Live System Variables
* **Directory Initialization:** On first launch, create `/storage/emulated/0/Konsole` and bind the global shortcut `$konsole` so it resolves in all commands (e.g., `cd $konsole/new` navigates to `/storage/emulated/0/Konsole/new`).
* **Customization Folder (`$konsole/customize`):** Generate default XML configuration files that dynamically control the app's look and behavior:
  * `ui.xml`: Terminal colors, font size, cursor style, transparency/wallpaper dim, grid column count for `kons apps`, and status bar visibility.
  * `behavior.xml`: Default shell mode (`konsole` vs `termux`), auto-complete keybindings, vibration feedback on keypress, and startup greeting.
  * `aliases.xml`: User-defined command aliases.
  * `variables.xml`: User-defined variables plus live system variables.
* **Live System Variables (1-Second Ticker):**
  * Run a background coroutine/handler updating every `1000ms` that refreshes system telemetry and writes/exposes them as accessible variables: `$time`, `$date`, `$battery`, `$cpu_cores`, `$cpu_usage`, `$ram_total`, `$ram_used`, `$ram_free`, `$storage_free`, `$device_name`, `$android_version`, `$ip_address`, `$wifi_ssid`, and `$network_type`.
* **Documentation File (`$konsole/variable.txt`):** Auto-generate a clean reference text file listing every available system variable name alongside a description of what value it holds.

---

# 5. Terminal UX & OS-Feel Enhancements
* **Fish-Shell Auto-Suggestions:**
  * Render dimmed inline ghost text ahead of the user's cursor in `TerminalView` predicting commands based on command history, built-in `Konsole OS` commands, aliases, and installed app names.
  * Bind the **Left Arrow** (or a dedicated extra-keys bar button / swipe gesture) to accept and auto-complete the suggested ghost text.
* **OS Experience Features:**
  * **Live Telemetry Header / Prompt:** A customizable dynamic prompt or pinned top HUD bar displaying live `$time`, `$battery`, `$ram_used/$ram_total`, and `$wifi_ssid` styled from `ui.xml`.
  * **App Drawer Dock / Quick-Launch Bar:** Custom extra-keys row configured for OS navigation (Apps Grid, Voice/Search, Auto-Complete, Mode Switch).
  * **Boot & Startup Script (`$konsole/customize/init.kons`):** Execute user-defined startup commands automatically when Konsole OS boots as the home launcher.

# 6. **Konsole Sudo & Device Lockdown (`lock`):**
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
