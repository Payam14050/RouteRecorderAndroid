# RouteRecorderAndroid

An independent Android GPS route-recorder project built on the established, open-source **OSMTracker for Android** codebase.

## Upstream foundation

- Upstream project: [labexp/osmtracker-android](https://github.com/labexp/osmtracker-android)
- Imported baseline: **v2026.02**
- License: **GNU GPL-3.0-only**. This project retains the upstream license and attribution; derivative distributions must comply with GPL-3.0.
- Upstream app capabilities include GPS track recording, waypoint markers, notes, photos, voice notes and GPX export.

The upstream source is imported by the repository workflow so that the complete, buildable Android project—not a hand-written mock-up—is the foundation here. The upstream README is retained as `UPSTREAM_README.md`.

## Build the Android APK

1. Open the **Actions** tab.
2. Select **Import upstream and build APK**.
3. Choose **Run workflow** on the `main` branch.
4. When the workflow finishes, download the `RouteRecorderAndroid-debug-apk` artifact from that run.

The workflow imports the pinned upstream release, runs the unit-test task, builds the debug APK, and publishes the APK as a downloadable artifact.

## Project status

The repository bootstrap is in place. The first upstream import and Android build are run through GitHub Actions; no APK is claimed until that workflow completes successfully.

## License and attribution

OSMTracker for Android is developed by the upstream contributors at [Laboratorio Experimental](https://github.com/labexp/osmtracker-android) and is licensed under GPL-3.0-only. See the imported `COPYING` and `LICENSES` files for license texts and notices.
