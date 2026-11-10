# Spoiler Shield

Personal Android app (sideloaded) that covers anything on screen matching a keyword regex —
built to dodge GTA 6 spoilers until the PC release.

- Accessibility service reads on-screen text (titles, captions, messages, notifications) and
  draws an opaque cover over matches. Can't read text inside images or videos.
- Keywords, "cover whole item" and on/off are set in the app; a Quick Settings tile pauses it.
- Everything runs on the phone; nothing is stored or sent.

Every push to `main` builds a signed APK with GitHub Actions and publishes it under Releases.
The signing key is committed so each build installs over the previous one.
