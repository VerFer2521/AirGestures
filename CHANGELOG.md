# Changelog

## 2.0 (2026-10-24)

### Added
- **Swipe left / right.** Make the L sign and point your index finger to the left or right to swipe the screen. Hold to repeat. Works in galleries, stories, home screen pages and tabs.
- **Voice swipes.** "swipe left", "swipe right", "scroll left", "scroll right", "next page", "previous page", "next photo", "previous photo", "next story", "previous story", plus "swipe up" and "swipe down".
- **Pinch + drag volume.** Pinch, then move your whole hand up or down to change the volume. One stroke can cover the whole range. A pinch held completely still still mutes (after 0.7 seconds).
- **New app icon.** A neon hand skeleton on a navy-violet background, matching the splash screen. Includes the adaptive icon for modern Android, a one-colour version for themed icons (Android 13+) and legacy icons for older phones.
- Guide screen cards and tips for all of the above.

### Changed
- **Fist** now goes **Back** by default (one action per hold, no repeating).
- **Open palm** now does nothing by default, because it is the pose the hand makes when it is raised to the camera.
- "Volume up" and "Volume down" were removed from the Settings action lists, because volume is now done with pinch + drag. Voice commands like "volume up" still work. Saved settings that used them fall back to the new defaults.
- Version 2.0 (build 3).

### Fixed
- "scroll left" and "scroll right" used to scroll down. They now swipe sideways.

## 1.0 (2026-10)

First public release.
- Hand gestures with on-device MediaPipe tracking, remappable in Settings
- Voice commands and Voice Autopilot (messages, search, click, type)
- Reel scrolling with the L sign, double tap with three fingers
- System actions: screenshot, back, recent apps, notifications, flashlight, volume, media keys, lock
- Hand-only animated stage, floating background panel, battery saver
- Gesture challenge game, practice mode, statistics, onboarding tour
