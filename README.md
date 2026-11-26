# CCTV Cam

An Android 9 camera for the Nokia 3 TA-1032. It serves H.264 over RTSP/TCP to VLC or a compatible NVR on your LAN or VPN.

## Watch in VLC

1. Open **CCTV Cam** and tap **Start streaming**. Grant camera access. Microphone access is requested only if you choose audio.
2. Tap **Copy RTSP URL**. The URL is `rtsp://<phone-ip>:8554/live`.
3. In desktop VLC choose **File → Open Network** (or **Media → Open Network Stream**), enter the URL, then enter the username and password shown under **Edit viewer credentials**. Set **Input/Codecs → RTP over RTSP (TCP)** if the player attempts UDP. For low delay on a reliable LAN, use 150 ms network caching.
4. Leave the app or turn off the display. Android’s foreground notification shows that the camera is enabled. **Stop streaming**, including Stop in the notification, also turns off automatic startup.

**Start streaming** saves the camera settings and enables streaming after an Android reboot. The service retries when the camera is temporarily busy. The phone advertises its current address in the app. A DHCP reservation keeps the URL stable. A player disconnected during reboot may need to reopen the feed. Android’s system-level Force stop suspends automatic launches until the app is opened again.

## Camera settings

Use **Edit viewer credentials → Save** to change the login at any time. Connected viewers must reconnect with the new credentials. Stop streaming to change the selected camera, audio, or preset. **Manual** unlocks resolution, rate, and bitrate independently. Mount the phone in landscape for the raw sensor orientation.

| Preset | Video | Target rate | Target bitrate |
| --- | --- | ---: | ---: |
| Low delay | 720×480 | 20 fps | 1 Mbps |
| Low heat | 640×480 | 15 fps | 512 kbps |
| High detail | 1920×1080 | 24 fps | 4 Mbps |

The app displays the measured encoded frame rate, aggregate RTSP send bitrate, and playing clients. Exposure, device temperature and Wi-Fi affect delivered frames. On first launch the app sets the username to `admin` and generates a unique 20-character password. Open **Edit viewer credentials → Show password** to see it. Updates preserve the existing login. Credentials are stored in app-private device-protected preferences so the service can start on the boot broadcasts. RTSP here is unencrypted. Use the LAN or an encrypted VPN for remote access.

## Build

Use JDK 17 or 21, Android SDK Platform 35, and Go 1.24 or newer. The wrapper builds the arm64 Android RTSP executable and packages it in the APK.

```sh
./gradlew assembleDebug
(cd server && go test ./...)
adb install -r build/outputs/apk/debug/CCTVCam-debug.apk
```

Camera2 captures directly into the MediaTek AVC encoder’s input surface. The app passes encoded H.264 units through a private pipe to gortsplib, which serves authenticated RTSP readers. Optional microphone audio is mono 16 kHz L16. The camera service uses Android APIs and requires no root. The feed can be consumed by a future browser gateway or motion recorder.

## Nokia TA-1032 checks

VLC 3.0.21 on macOS authenticated over TCP. During 30-second rear-camera comparisons with a 150 ms network cache, VLC displayed 295 frames at 1280×720 with a 24 fps target, and 615 frames at 720×480 with a 20 fps target. Both resolutions used hardware H.264 encoding. Front-camera H.264 and optional L16 audio were also detected. An incorrect password received an RTSP 401.

The installed build restored the stream on a real Android reboot without opening the app. During the subsequent screen-off run, the first 60 seconds showed 762 pictures, 16 lost pictures, 43°C battery temperature and heavy system-wide CPU use immediately after boot. The Mac then lost both the RTSP connection and wireless ADB. This interrupted the 30-minute test. The newer APK adds a Wi-Fi lock for the duration of streaming. Its installation and overnight reliability have not been verified. Optical latency measurement was deferred.

## Power on after using the Nokia power menu

The separate **Nokia 3 automatic power-on** Magisk module is specific to the TA-1032 running the tested MediaTek kernel. With the external supply continuously on, selecting **Power off** automatically schedules a hardware power-on alarm ten seconds ahead. The watcher refreshes its own alarm while Android shuts down, so shutdown time and Android boot time are additional. The regular Android camera app handles streaming after boot.

To leave the phone off, disable the module in Magisk **before** selecting **Power off**. The watcher checks Magisk’s disable marker when shutdown begins. It listens for Android’s user-requested shutdown signal and preserves an existing hardware power-on alarm. Abrupt power loss, crashes, thermal shutdown, and low-level commands that bypass the Android power menu are outside this behavior. Module source is in `poweron/`. The last arming result is stored in `/data/adb/modules/nokia_poweron/last-shutdown`.
