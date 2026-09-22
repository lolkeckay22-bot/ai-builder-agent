---
name: android-app-builder
description: Create, edit, compile, repair, and return Android APK projects when the user asks for an Android application or changes to one.
---
# Android App Builder

1. Turn the request into explicit acceptance criteria.
2. Ask independent planner, Android specialist, and reviewer agents for scoped reports when the task has multiple concerns.
3. Work only inside the task workspace. Preserve supplied files and user changes.
4. Generate or edit the smallest complete project that satisfies the request.
5. Build the project. Read the actual compiler output, patch the cause, and retry up to three times.
6. Verify that the APK exists, is non-empty, and has a SHA-256 checksum.
7. Return the real artifact. Never replace a requested file with instructions or claim success without a successful build.
