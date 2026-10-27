# Dockside Laucnher for FireTV OS

A tvOS-inspired custom home screen for Fire TV sticks that no longer get updates.
Replaces Amazon's home screen with a clean row of pinned apps, a second row for
everything else, and a few customisation options. No root required.

## Features

- **4 pinned apps** on the first row, chosen by you
- **Second row** with all your other apps, tucked away until you press Down
- **Customise button** to change the pinned apps and the background
- **8 built-in gradient backgrounds**, or use a photo stored on the device
- App banner artwork on tiles, big focus animation, and the app name shown above the dock
- Clock, Wi-Fi and Settings shortcuts

## Tested on

- Fire TV Stick HD (1st gen), Fire OS 7.7.1.7 (PS7717)

It should work on other Fire TV / Android TV devices, but I haven't tested them.
Please open an issue to report what works for you.

## Install

1. On the Fire TV: **Settings > My Fire TV > About**, click the device name 7 times
   to unlock Developer Options. Then turn on **ADB debugging** and
   **Apps from unknown sources**.
2. Download the APK from the [Releases](../../releases) page, or build it yourself (below).
3. Install it from a PC with [ADB / SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools):

   ```
   adb connect FIRE_TV_IP:5555
   adb install -r TvLauncher.apk
   ```

## Make it your Home screen

Fire OS does not let you disable Amazon's launcher, so pressing Home normally goes
back to Amazon's screen. The workaround is **Launcher Manager Mini**, a separate
free app by SweenWolf. It is not included here, so get it from the
[XDA thread](https://xdaforums.com/t/app-firetv-noroot-launcher-manager-change-launcher-without-root.4176349/).

1. Install Launcher Manager Mini with `adb install`.
2. Grant its permissions:

   ```
   adb shell pm grant com.wolf.minilm android.permission.READ_LOGS
   adb shell appops set com.wolf.minilm SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +com.wolf.minilm
   ```
3. Open Launcher Manager Mini, choose **My Launcher**, generate and enter the PIN
   if asked, tick **Launch on Boot**, and enable the custom launcher.
4. Press Home. If nothing happens, try the other trigger keys in the app.

Note: the XDA thread says some newer Fire OS builds block this method. It worked
on the firmware listed above.

## Using it

- **Left / Right / Down / Up** to move around. Down from the first row reveals the second.
- **Back** from the second row returns to the first.
- **Sliders icon** (top right) opens Customise: change pinned apps or the background.
- **Photo backgrounds:** copy a picture to the stick (`adb push photo.jpg /sdcard/Pictures/`).
  If no permission prompt appears, run
  `adb shell pm grant com.myfiretv.launcher android.permission.READ_EXTERNAL_STORAGE`.

## Build from source

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder and let Gradle sync.
3. **Build > Generate App Bundles or APKs > Generate APKs**. The APK is
   `app/build/outputs/apk/debug/app-debug.apk`.

## Undo / uninstall

```
adb uninstall com.myfiretv.launcher
```

Then uninstall Launcher Manager Mini, and Home goes back to Amazon's screen.

## Known limitations

- The frosted-glass panels are translucent, not a true blur.
- The Settings and Wi-Fi shortcuts use Fire OS screen names that may differ between versions.
- Debug-signed build only, and updating needs the same signing key.
- This is a hobby project, so use it at your own risk.

## Disclaimer

Not affiliated with or endorsed by Amazon or Apple. "Fire TV" is a trademark of
Amazon, and "Apple TV" and "tvOS" are trademarks of Apple. This project is only
inspired by that style of interface.

## License

MIT, see [LICENSE](LICENSE).
