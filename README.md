# EyeDropperAnywhere

A public-API implementation of the Android 17 system EyeDropper that can pick a color over other
apps. It adapts the AOSP reticle and sampling behavior while replacing privileged platform APIs
with APIs available to a regular installed application.

## How it works

1. The app requests notification and `SYSTEM_ALERT_WINDOW` access.
2. Android shows its standard MediaProjection consent dialog for the current screen.
3. A `mediaProjection` foreground service captures and monitors the current display.
4. A `TYPE_APPLICATION_OVERLAY` window displays the AOSP Compose reticle and controls.
5. Confirming copies the selected `#RRGGBB` value to the clipboard and stores it in the app.

The AOSP picker is rendered at full opacity in a fullscreen overlay window. The complete assembly
can be dragged from the magnifier, arrows, panel, buttons or handle; short button taps still confirm
or cancel. Only the picker bounds are touchable, so touches outside them pass through to the app
underneath. MediaProjection continuously refreshes the transparent 7×7 sample area inside the
picker handle without hiding the overlay.

The main screen is built from Material 3 `ListItem` components. A Quick Settings `TileService` is
included and can be added from the app on Android 13+ or from the system tile editor.

## Build

```shell
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Platform limitations

- Android requires fresh user consent for each MediaProjection session; an ordinary app cannot
  silently bypass the system dialog.
- Secure or protected content is redacted by Android.
- `TYPE_APPLICATION_OVERLAY` is below trusted system windows, unlike AOSP's privileged
  `TYPE_SCREENSHOT` overlay.
- Live refresh is limited to the transparent 7×7 sample area so the picker does not capture its
  own rendered controls.

## AOSP attribution

The touchscreen Compose UI, dimensions, 7×7 magnified patch, adaptive positioning and interaction
model are ported from [AOSP EyeDropper, Android 17 branch](https://android.googlesource.com/platform/packages/apps/EyeDropper/+/refs/heads/android17-release/),
licensed under Apache License 2.0. See `NOTICE` and source-file headers.
