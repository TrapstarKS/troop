# Strap firmware guidance

Troop displays the version reported by each strap through its existing attributed version resolver. A cached value is a last report, not a live verification; a strap that has not reported a version remains unknown. A user-entered reference is only a local numeric comparison, not an authoritative latest-version catalog or an update-eligibility check. No latest-version service is polled.

Firmware updates are installed by the **official WHOOP app**. Use WHOOP's [current update guide](https://support.whoop.com/s/article/WHOOP-3-0-and-4-0-How-to-Update-Your-Product-s-Firmware) and check the app's Device Settings → Advanced Settings → Firmware Check. Vendor screens and eligibility can change; account access may be required. Troop does not log in for the user or promise membership eligibility.

Before switching apps, export a `.noopbak`, let an in-progress offload finish, and use troop's existing pause/disconnect controls so the two apps do not compete for the strap. Opening the help view does not disconnect, pair, reboot, or send commands. Follow the vendor's charge, proximity, and connectivity instructions. After WHOOP finishes, return to troop and reconnect through the existing path to observe a new report. Use the existing re-pair help if needed; a brief vendor-update disconnect does not prove a troop fault.

## Experimental simulation

The optional **Firmware update (simulation)** flow is off by default and always labeled **SIMULATION — no strap is changed**. Apple enables it in Settings → Experimental; Android exposes its session-only toggle in Test Centre. Check, download, verify, transfer, and restart stages are names for in-memory scripted events. Nothing is downloaded, verified as vendor firmware, transferred, or rebooted. Failure injection, pause/resume, retry, and cancellation operate only on the pure simulation state.

The Swift `FirmwareSimulation` package and Android `com.noop.firmware` helpers have no Bluetooth or networking dependencies, image loader, device registry, firmware bytes, or production transport interface. The UI gives them no hardware handle. Simulated results cannot change the reported firmware, sync state, database, or `.noopbak` contents. Unit tests cover transition/failure behavior and shared traces.

Real firmware upload/DFU and WHOOP firmware redistribution remain excluded by [the BLE safety contract](CONTRIBUTING.md#the-ble-safety-contract-read-this-before-touching-bluetooth). The simulation does not authorize or scaffold a real updater. Existing safe firmware-version reads still require separate physical-strap validation; no hardware firmware operation is tested here.
