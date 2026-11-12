# To do: Android

## First: build and run 0.3.0

Version 0.3.0 was written without a compiler and without a phone at hand. In this order:

- It compiles. The new dependencies resolve: `dev.rikka.shizuku:api` and `provider` 13.1.5, `org.lsposed.hiddenapibypass:hiddenapibypass` 4.3.
- A connected headset shows its level. If not, the text under Settings, About, Diagnostics tells which source stayed silent: whether advertisements arrive at all, whether `isConnected` and `getBatteryLevel` answer, which profile services Android handed over.
- AirPods levels arrive from advertisements, with the application on screen (the scan takes everything) and with the screen off (the scan carries filters). The scan permission is declared `neverForLocation`; if Android filters these advertisements because of it, the flag has to go and the location permission has to be asked for.
- Connect and disconnect: as an ordinary application (expected to be refused from Android 12), through Shizuku, with root. The helper (`elevated/Helper.kt`) rests on assumptions about `app_process` that only a run can confirm: that the Bluetooth module can be reached there, and that the shell user is allowed to connect a device.
- The codec: read directly up to Android 12, from `dumpsys bluetooth_manager` with Shizuku or root later. The parser was written from memory of that output.
- The GATT battery read on a Bluetooth LE mouse or keyboard, and that it leaves the device's own link alone.
- All four designs in light and dark, with the system accent and with Bluetooth blue: every screen and every settings tab. The controls of Fluent, Holo and Android 2.1 are drawn from memory and were never seen on a screen: sizes, colours and proportions need a look and a correction pass.
- The status bar icons following the theme of the application.

Still open from before:

- Notifications: the ongoing one, a headphone connecting, a low battery.
- The updater end to end: the "install unknown apps" screen, the download with its progress, Android's confirmation, the notification when the application is in the background, the silent update on Android 12 and later, the service coming back after the update.
- The widget in every layout, detail level, background and theme, in light and dark, at several sizes, in every design.
- The service starting with the phone.
- A signed release installing over an earlier one.

## Devices and data

- AAP over L2CAP for AirPods: noise control, conversation awareness, ear detection, exact levels. Android has no public way to open a classic L2CAP channel; the known route needs the hidden socket constructor and, on most phones, root.
- Samsung Galaxy Buds, Sony, Xiaomi, Huawei: per-earbud levels over their own protocols on a serial channel. Needs a parser in the core and an RFCOMM transport here.
- Battery of game controllers that report it only in a HID report. Android gives applications `InputDevice.getBatteryState` from Android 12; not used yet.
- Tell the vendor of a paired device, to stop relying on names. Android keeps the product identifier from applications.
- Do the codec read for the privileged path without `dumpsys`, whose format is not a contract.
- Pair a new device from the application.

## Widget

- A configuration screen per widget instance, so two widgets can differ. Today one configuration serves them all.
- A resizable layout that drops detail as the widget shrinks.
- A refresh button, and a tap on a device opening its page.
- Outlines of the earbuds and the case, as on Windows.
- Connect and disconnect from the widget.

## Application

- Icons for the kinds of device in the lists.
- Language choice inside the application. Android 13 and later offer it in the system settings already.
- A tile in Quick Settings.
- Material You: a navigation bar at the bottom in place of the two top tabs, and a layout for tablets and foldables.
- The dialogs and menus of the historical designs. The list a spinner opens is still the Material menu in the design's colours.
- Shrink the release with R8, keeping `btmanage.core.Native` and `elevated.Helper`.
- Tests: the JSON mapping in `Devices`, the notification rules in `DeviceService`, `parseCodecs`.

## Distribution

- Google Play and F-Droid. The use of Shizuku, root and calls outside the public interface needs a second look against Google Play's rules.
- A release without the debug fallback: fail the build when the signing secrets are missing on a tag.

## Beyond Bluetooth devices

- AirPlay sender and receiver.
- AirDrop.
- Clipboard and notification sync with the desktop clients.
