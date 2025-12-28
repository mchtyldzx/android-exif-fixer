# Android Exif Fixer

**Android Exif Fixer** is a utility tool designed to solve the common **"Timeline"** sorting issue in Android galleries (specifically Xiaomi Gallery).

Copying files between folders or restoring backups (like from WhatsApp) can reset photos and videos dates, causing them to
appear under "Today" in the gallery timeline **even if they** were taken years ago. So this app scans your media files and 
restores the correct `Last Modified` date using internal **EXIF metadata** (for photos) or **Media Metadata** (for videos).



##  🛠️ Build & Run

### Requirements

- **Android Studio** (Ladybug or newer recommended).
- JDK 17 or higher.
- Android 11+ Device or Emulator.

### Set up Project

1.  Clone or Download this repository.
2.  Open **Android Studio**.
3.  Select **Open** and choose the project folder.
4.  Wait for Gradle Sync to complete.

### Run on Device

1.  Enable **Developer Options** on your phone:
    - Go to _Settings > About Phone_.
    - Tap _Build Number_ 7 times.
2.  Enable **USB Debugging**:
    - Go to _Settings > System > Developer Options_.
    - Turn on _USB Debugging_.
3.  Connect your phone to PC via USB cable.
4.  In Android Studio, select your device from the dropdown menu (top toolbar).
5.  Click the Green **Run** button (or press `Shift + F10`).

---

## Features

- **Scope Storage Bypass**: Uses advanced permissions to modify file dates on Android 11+ without Root.
- **Universal Support**: Works with JPG, PNG, HEIC, MP4, MOV, MKV.
- **Xiaomi Optimized**: Specifically handles Xiaomi's proprietary FileProvider caching and MediaStore database to ensure immediate timeline updates.
- **Privacy Focused**: Runs entirely offline on your device. Zero data collection.
- **Safe**: Does not modify your photo/video content, only the file timestamps.

##  How It Works

1.  **Select Folder**: Choose a directory (e.g., `DCIM/Camera`).
2.  **Scan**: The app identifies media files where the "Last Modified" timestamp incorrectly differs from the actual "Date Taken".
3.  **Fix**:
    - Updates the file system timestamp (`File.setLastModified`).
    - Updates the Android `MediaStore` database to force the Gallery to refresh.
    - Triggers the system `MediaScanner`.
