# Karoo 3 AI Cycling Pro (`karoo-ext` Extension + All-Metrics Window)

An Android extension and dedicated multi-metric cockpit for the **Hammerhead Karoo 3**, built on the official [`io.hammerhead:karoo-ext`](https://github.com/hammerheadnav/karoo-ext) SDK.

## Key Capabilities

1. **Native `karoo-ext` Data Fields (for Karoo Ride Pages)**:
   - **Relative Wind Vector HUD**: Live Headwind/Tailwind (`km/h`), Crosswind (`km/h`), Relative Wind Angle (`deg`), and Aero Drag Power Penalty (`±Watts`).
   - **On-Device AI Adaptive Power**: Dynamic target power (`W`) adjusted for fatigue, cardiac drift, grade, and wind.
   - **Skiba W' Balance Gauge**: Real-time anaerobic work capacity remaining (`kJ` & `%`).
   - **NP / IF / TSS Pro Tile**: TrainingPeaks-style Normalized Power, Intensity Factor, and Training Stress Score in a single tile.
   - **AI Smart Fueling & Hydration Timer**: Real-time carbohydrate (`g/hr`) and fluid (`ml/hr`) countdown based on kilojoule burn rate and ambient heat/humidity.
   - **Bonus Action (`open-all-metrics-window`)**: Launch the dedicated All-Metrics Pro Window straight from your SRAM AXS / Shimano Di2 hood buttons or the Karoo Control Center.

2. **Dedicated All-Metrics Pro Window (`MetricsDashboardActivity`)**:
   - Full-screen high-contrast cockpit tailored to the Karoo 3's `480x800` anti-glare display.
   - Combines **Strava** (Live Segment Virtual Partner delta, VAM, Grade %), **TrainingPeaks** (3s Power, 30s Rolling Normalized Power, IF, TSS, kJ), **Zwift** (color-coded 7-Zone Power bar & HR Zones), **MyWindsock** (GPS Heading vs. Open-Meteo Wind Vector rose), and **TrainerRoad-inspired On-Device AI Heuristics** in one screen.

3. **Key-less Open-Meteo Wind & Weather Engine (`WindWeatherEngine.kt`)**:
   - Queries `https://api.open-meteo.com/v1/forecast` using live Karoo GPS coordinates (zero API key required).
   - Computes true GPS bearing between coordinate samples and decomposes wind into axial (Headwind/Tailwind) and lateral (Crosswind) vectors.

---

## How to Build the `.apk`

### Method 1: Zero-Setup Cloud Build via GitHub Actions (Recommended)
1. Create a new repository on GitHub and push this folder to the `main` branch.
2. Add a GitHub Personal Access Token (classic) with `read:packages` scope as a repository secret named `GH_PACKAGES_TOKEN` (required by GitHub Packages to download Hammerhead's public `io.hammerhead:karoo-ext` dependency).
3. Open the **Actions** tab in your GitHub repository and run the **Build Karoo 3 APK** workflow.
4. Download `karoo3-ai-cycling-pro-debug.apk` from the workflow **Artifacts** section.

### Method 2: Local Build with Android Studio / Gradle
1. Install **Android Studio Ladybug (or newer)** with **JDK 17** and **Android SDK Platform 34**.
2. Add your GitHub username and a Personal Access Token (`read:packages` scope) to `~/.gradle/gradle.properties` or environment variables:
   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=ghp_YOUR_GITHUB_PAT_WITH_READ_PACKAGES
   ```
3. Run the Gradle build from the project root:
   ```bash
   ./gradlew assembleDebug
   ```
4. Your installable APK will be generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## How to Install (Sideload) on Hammerhead Karoo 3

1. **Via Hammerhead Companion App (Karoo 3)**:
   - Open the `.apk` file (or share the GitHub Release URL) on your phone and select **Share -> Hammerhead Companion App**.
   - Confirm installation on your Karoo 3 screen.
2. **Via ADB (USB-C)**:
   - Enable **Developer Options -> USB Debugging** on your Karoo 3.
   - Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
