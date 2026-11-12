# To do: Android

## First: run it

Nothing here has been started on a phone. To confirm, in this order:

- The application starts, asks for its permissions, and the service comes up with its notification.
- Paired devices are listed with the right kind and connection state. Connection state and the system battery level use calls outside the public interface.
- AirPods levels arrive from advertisements, with the screen on and off. The scan permission is declared `neverForLocation`; if Android filters these advertisements because of it, the flag has to go and the location permission has to be asked for.
- Notifications: the ongoing one, a headphone connecting, a low battery.
- All four designs (Material You, Fluent, Android 4.3, Android 2.1) in light and dark, with the system accent and with Bluetooth blue: every screen and every settings tab. Fluent and the two historical looks were written without ever being seen on a screen.
- The updater end to end: the "install unknown apps" screen, the download with its progress, Android's confirmation, the notification when the application is in the background, the silent update on Android 12 and later, the service coming back after the update.
- The widget in every layout, detail level, background and theme, in light and dark, at several sizes, in both designs.
- The service starting with the phone.
- A signed release installing over an earlier one.

## Devices and data

- Match AirPods to their pairing by something better than the name. Android does not give applications the product identifier.
- AAP over L2CAP for AirPods: noise control, conversation awareness, ear detection, exact levels. Android lets an application open the channel from API 29.
- Battery over GATT for Bluetooth LE devices Android holds no level for.
- The audio codec in use (`BluetoothA2dp.getCodecStatus`, restricted since Android 13).
- Connect and disconnect from the application. There is no public call; the profile proxies' hidden ones need a privileged application.

## Widget

- A configuration screen per widget instance, so two widgets can differ. Today one configuration serves them all.
- A resizable layout that drops detail as the widget shrinks.
- A refresh button, and a tap on a device opening its page.
- Outlines of the earbuds and the case, as on Windows.

## Application

- Icons for the kinds of device in the lists.
- Language choice inside the application. Android 13 and later offer it in the system settings already.
- A tile in Quick Settings.
- The historical designs go only as far as colours, shapes and type. Closer likeness needs drawn controls: the gradient buttons and tabs of Android 2.1, the Holo switch and its blue underlines.
- Shrink the release with R8, keeping `btmanage.core.Native`.
- Tests: the JSON mapping in `Devices`, the notification rules in `DeviceService`.

## Distribution

- Google Play and F-Droid.
- A release without the debug fallback: fail the build when the signing secrets are missing on a tag.

## Beyond Bluetooth devices

- AirPlay sender and receiver.
- AirDrop.
- Clipboard and notification sync with the desktop clients.
