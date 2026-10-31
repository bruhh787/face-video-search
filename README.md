# Face Video Search (Android)

Scans every video on your phone, finds the faces in them, and lets you search by uploading a photo of a person.
Also filters videos by duration (e.g. under 5 seconds). Everything runs on the phone, nothing is uploaded.

## Build the APK with GitHub (no Android Studio needed)
1. Create a free account on github.com, then New repository (name: face-video-search, Public or Private).
2. On the repo page: Add file -> Upload files. Drag in EVERYTHING inside this folder (including the hidden-looking `.github` folder), then Commit.
3. Open the Actions tab. "Build APK" starts by itself (about 5-10 min). If it doesn't, click it -> Run workflow.
4. When it turns green, open the run, scroll to Artifacts, download `face-video-search-apk` (a zip), unzip it, copy `app-debug.apk` to your phone.
5. On the phone open the APK, allow "Install unknown apps" for your file manager/browser when asked, install.

If the build turns red: open the failed step, copy the last ~40 lines of the log and send them to Claude.

## Build with Android Studio instead
1. Download https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/master/app/src/main/assets/facenet_512.tflite
   and put it in `app/src/main/assets/`.
2. Open this folder in Android Studio, wait for Gradle sync, Build -> Build APK(s).

## Using the app
1. Allow video access. 2. "Scan all videos" (first time is slow; it keeps running in the background, you can Stop and resume).
3. "Pick face photo(s)" and results show matching videos, best match first. Tap a tile to open the video.
4. Adjust "Match strictness" if you get wrong people (raise it) or missed videos (lower it).
