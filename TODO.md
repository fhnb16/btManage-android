# To do: Android

## First: AirPods levels over the accessory protocol

0.4.0 on a Pixel 7 Pro with Android 16: the channel opens, noise control works, 34 packets arrived, and none of them gave a level. 0.4.1 opens the conversation as the LibrePods application does (feature flags `D7`, every notification asked for, all of it twice), where 0.4.0 followed the LibrePods description. If the levels still do not come, the diagnostics now list the packets by operation code with the last of each: the battery packet is code `04`, 22 bytes for a pair of earbuds and a case.

Advertisements on the same phone: thousands of Apple messages of other types arrived, not one of type `07`, the one with the levels. Why the phone does not hear them, while a Windows computer does, is not known.

## Earlier: AirPods show no levels

Open since the first run, through 0.2.0 and 0.3.0, on the maintainer's phone, while the Windows application shows the same AirPods fine. The cause is not known: read as code, the path from the scan to the core is the one Windows uses. Ruled out: the `neverForLocation` flag (CAPod, OpenPods and LibrePods declare the scan the same way).

What 0.4.0 changed about it, none of it confirmed: the message is taken from the raw advertising packet; the scan listens all the time while the application is on screen; a pairing is matched without its name, by the service Apple's accessories offer; a model the core's table does not list still matches; the earbuds the user has connected are taken in at any signal strength.

What settles it is the text under Settings, About, Diagnostics, copied with the AirPods connected and in the ears and the application on screen for a minute. It tells the cases apart:

- "advertisements: 0 total": Android delivers nothing to the scan.
- Advertisements arrive, but "Apple message types" has no `07`: the AirPods do not broadcast, or Android does not pass that message on.
- `07` is there, "list: … 0 from advertisements": the core turns the message down; "last message with levels" shows its first bytes.
- Levels from advertisements are there, but not on the AirPods: the matching; the line of the pairing says how it was marked ("Apple: name", "product", "service" or "no").

## Android TV (0.5.0)

Written without a TV at hand. To try on one:

- The tile in the row of applications (`drawable/tv_banner.xml`), and that the application starts from it.
- The remote alone: from the start, the first press of an arrow lands somewhere sensible; every control of every screen and settings tab is reached and shows the highlight (`Modifier.focusRing`) in all four designs; Back leaves a device page and closes the dialog of a new device.
- The two tabs: the arrows between Devices and Settings, and that the pages do not slide over each other while the focus moves.
- Lists scroll along with the highlight.
- The margins at the edges of the picture, and the width of the content.
- The Bluetooth permissions and the foreground service on a TV, and what Android TV allows in the background.

## Then: build and run 0.4.0

Versions 0.3.0 and 0.4.0 were written without a compiler and without a phone at hand. In this order:

- It compiles, the core included: `btm_hub_cell` and its JNI twin `hubCell` are new in core 0.3.0, and the Android application will not link against an older core.
- A connected headset shows its level. If not, the diagnostics text tells which source stayed silent: whether `isConnected` and `getBatteryLevel` answer, which profile services Android handed over.
- Adding a device: the search finds a headset in pairing mode and a Bluetooth LE mouse, a tap brings up Android's pairing dialog, the device lands in the list. On Android 11 and earlier with Location off, the note about it shows.
- The panel a tap on the widget opens: half the screen, the home screen dimmed and blurred behind it, closes on a tap beside it and on Back, both buttons, the search inside it, in all four designs.
- The swipe between Devices and Settings, and that it does not fight the scrolling row of tabs inside Settings.
- Android's record of a device through Shizuku: levels of each earbud for Fast Pair earbuds, maker, model, firmware. "Android's record" in the diagnostics says how the read went. It rests on the shell user being allowed to read it, which only a run confirms.
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

- AAP for AirPods (`service/Aap.kt`, the packets in the core) is written: exact levels, noise control, conversation awareness. To try: that the channel opens on a phone with a fixed Bluetooth stack (Pixel on Android 16 QPR3 or later, recent OnePlus, OPPO, realme), that the levels of left and right are not swapped (the LibrePods description contradicts itself there), that the mode follows the AirPods when it is changed on the buds themselves, and what the section says on a phone where the channel fails. Ear detection is not read yet: the description names the buds primary and secondary, not left and right.
- Noise control from the panel the widget opens. It shows the mode, not a switch for it, because four choices do not fit in a row at half the screen.
- Makers' own protocols on a serial channel, for the levels of each earbud where Fast Pair does not give them: Samsung Galaxy Buds, Huawei FreeBuds, Sony, Nothing, Soundcore, Xiaomi. Each needs a parser in the core, written from the public descriptions (GalaxyBudsClient, OpenFreebuds, OpenSCQ30 and the like), an RFCOMM transport here, and a pair of the earbuds to try it on: the layouts differ from model to model.
- The Fast Pair message stream (RFCOMM, one protocol for every Fast Pair device: levels, model, ringing a lost earbud). Google's own service usually holds that channel, so it is worth trying only where Google's service is absent.
- Match the levels in Fast Pair advertisements to the paired device. Today they are listed as a device of their own.
- More of what the Apple message says: lid open or shut, the colour, which earbud holds the microphone.
- The Device Information Service of Bluetooth LE devices (maker, model, firmware), read along with the Battery Service.
- Battery of game controllers that report it only in a HID report. Android gives applications `InputDevice.getBatteryState` from Android 12; not used yet.
- Tell the vendor of a paired device, to stop relying on names. Android keeps the product identifier from applications.
- Do the codec read for the privileged path without `dumpsys`, whose format is not a contract.
- In the search for a new device, say what kind of device each one is.

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
