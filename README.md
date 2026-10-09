<div align="center">
  <h1>Paperize Fold</h1>
  <p><strong>A fork of <a href="https://github.com/Anthonyy232/Paperize">Paperize</a> built for book-style foldables like the Galaxy Z Fold</strong></p>
</div>

> **Made with AI.** Every PaperizeFold change in this fork was written by Claude (Anthropic's AI) in conversation with me, BigJonGoodwin. I described what I wanted on my Z Fold 6 and tested it; Claude wrote the code, tests and build setup. The original Paperize app is by [Anthonyy232](https://github.com/Anthonyy232) and all credit for it goes to them. Please report fork issues here, not upstream.

## Why this fork

On Samsung foldables, an app can only set the wallpaper of the screen that's in use. Paperize changes the main screen, and the cover screen falls behind (or the other way round). This fork keeps both screens matched and adds tools for setting up each screen.

## What's different from Paperize

- **Fold sync** — after you fold or unfold, the screen you're using is updated to the current wallpaper. The other screen's image is prepared ahead of time so the switch is quick. It remembers what each screen shows and only rewrites a screen that's out of date.
- **Both-screens preview** — the main and cover screens side by side at their real shapes, with scaling and effects shown live as you move the sliders.
- **Separate cover screen look** — optional scaling and effects just for the cover screen.
- **Unfolded layout** — the preview stays beside the controls on the big screen.
- **Quiet changes** — scheduled changes wait until the screen is off and nothing is playing, so theming apps (Material You, ColorBlendr) don't recolor while you're watching something. Effect edits apply once when you leave the app.
- **Settings → Foldable screens** — turn fold sync on/off, fast fold, wait for media, and a **Resync screens** button.

Works on regular phones too (quiet changes and the effects preview); fold features only appear on foldables.

## Install

Download the latest APK from [Releases](https://github.com/BigJonGoodwin/PaperizeFold/releases), or add this repo to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates. It installs alongside the original Paperize (different app ID) and is signed with this fork's own key.

---

## Original Paperize README

<div align="center">
  <img style="display: block" src="https://github.com/user-attachments/assets/e8fb14f5-ec8e-440e-a2ac-8065322b0e28" alt="">
  <h1>Paperize</h1>
  <p><strong>A dynamic wallpaper changer that keeps your device's aesthetic fresh and exciting</strong></p>

  [![GitHub Downloads](https://img.shields.io/github/downloads/Anthonyy232/Paperize/total?style=flat&logo=github&label=Downloads)](https://github.com/Anthonyy232/Paperize/releases)
  [![GitHub Release](https://img.shields.io/github/v/release/Anthonyy232/Paperize?style=flat&logo=github)](https://github.com/Anthonyy232/Paperize/releases/latest)
  [![License](https://img.shields.io/github/license/Anthonyy232/Paperize?style=flat)](LICENSE)
  [![F-Droid](https://img.shields.io/f-droid/v/com.anthonyla.paperize?style=flat&logo=fdroid)](https://f-droid.org/en/packages/com.anthonyla.paperize/)
  
  [![Crowdin](https://badges.crowdin.net/paperize/localized.svg)](https://crowdin.com/project/paperize)
</div>

---

## Features

- **Dynamic Wallpaper Changer** — Set your wallpaper to change at specific time intervals
- **Static & Live Wallpapers** — Choose between traditional static wallpapers or smooth live wallpaper transitions
- **Multiple Image Formats** — Supports JPG, PNG, WEBP, AVIF, HEIC/HEIF, BMP, GIF, TIFF, and SVG
- **Folder Support** — Organize wallpapers into folders for auto-updating
- **Dual Screen Support** — Choose the same or separate albums for home and lock screen
- **Wallpaper Effects** — Apply various effects including brightness, blur, scaling, vignette, and more
- **On-Device Storage** — All wallpapers and settings stored locally on your device

---

## Download

[<img src="https://github.com/Anthonyy232/Paperize/assets/60626873/1c034414-21cd-4a0a-838d-89fe7bd56910" alt="Download from GitHub" height="60">](https://github.com/Anthonyy232/Paperize/releases)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="60">](https://f-droid.org/en/packages/com.anthonyla.paperize/)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="60">](https://apt.izzysoft.de/fdroid/index/apk/com.anthonyla.paperize)

---

## Localization

Help translate Paperize into your language! Currently, most translations are provided using machine translation — contributions from native speakers are greatly appreciated.

**[Contribute on Crowdin →](https://crowdin.com/project/paperize/invite?h=d8d7a7513d2beb0c96ba9b2a5f85473e2084922)**

---

## Tech Stack

| Category | Technology |
|----------|------------|
| **Language** | [Kotlin](https://kotlinlang.org/) |
| **UI Framework** | [Jetpack Compose](https://developer.android.com/develop/ui/compose) |
| **Design System** | [Material 3](https://m3.material.io/) |
| **Dependency Injection** | [Dagger Hilt](https://dagger.dev/hilt/) |
| **Database** | [Room](https://developer.android.com/training/data-storage/room) |
| **Image Loading** | [Coil](https://coil-kt.github.io/coil/) |

<details>
<summary><b>View all dependencies</b></summary>

- [Zoomable](https://github.com/usuiat/Zoomable) — Zoomable and pannable views

</details>

---

## Architecture

- `AlbumRepository` owns library mutations. Imports, reordering, removal, covers,
  and queue invalidation use Room transactions; provider scans run outside them.
- `DocumentSource` isolates Android permissions and document-provider queries.
  Import and refresh use cases consume metadata without accessing Android providers.
- `WallpaperRepository` owns rotation queues and current-wallpaper records.
  Queue creation checks and writes in one transaction, including synchronized screens.
- `WallpaperController` shares static wallpaper application between the service
  and worker. Callers hold `WallpaperChangeLock` through application and schedule
  updates. `WallpaperRenderer` owns image processing; the controller recycles applied bitmaps.
- Operation `Result` values contain success or failure. Progress belongs to UI state,
  and coroutine cancellation propagates instead of becoming a failure result.
- Room migrations preserve versions 1–3 when upgrading to version 4. New schema
  changes must include a migration; destructive fallback is disabled.

## Building from Source

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 17 |
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.7.1 |
| Compile SDK | 37 (Android 17) |
| Minimum SDK | 31 (Android 12) |
| Target SDK | 36 |

### Build Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/Anthonyy232/Paperize.git
   cd Paperize
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select `File > Open` and navigate to the cloned repository

3. **Build and Run**
   - Click `▶ Run` to build and install on a connected device, or
   - Select `Build > Generate Signed Bundle / APK` to create a signed release

---

### Verification

Set `ANDROID_HOME` to your Android SDK directory, or configure `sdk.dir` in
`local.properties`. Use the checked-in Gradle wrapper:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

On Windows, use `gradlew.bat`. Device tests require a running Android emulator or
connected device (API 31 or newer). They cover rendering, scheduling, document
provider failures, import rollback, album cleanup, and library controls.

## Contributing

Contributions are welcome! Feel free to:

- Report bugs by opening an issue
- Suggest features or improvements
- Submit pull requests

---

## Support

If you find Paperize useful, consider supporting development through [GitHub Sponsors](https://github.com/sponsors/Anthonyy232) (one-time or monthly). Thank you!

---

## License

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
