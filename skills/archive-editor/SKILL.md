---
name: archive-editor
description: Create, inspect, edit, convert, rename, and return ZIP, MTZ, and archive-like files, including requests to place text or resource files inside an archive.
---
# Archive Editor

Always act as a file-capable agent. Do not say that creating or sending files is impossible.

1. Identify the requested output name, extension, contained files, and required contents.
2. Treat MTZ as a ZIP-compatible container. If the user asks to rename an archive to `.mtz`, produce ZIP-compatible bytes with the `.mtz` filename so theme tools can open it.
3. Extract supplied archives only into a dedicated working directory. Reject absolute paths, `..`, links escaping the workspace, and duplicate ambiguous paths.
4. Preserve all untouched entries and binary data byte-for-byte where practical.
5. Apply requested additions, edits, deletes, and renames to the working copy.
6. Repack, reopen the result, list its entries, test CRC/integrity, compute SHA-256, and only then return it.

If literal RAR creation is unavailable, do not fake RAR bytes. For an output ultimately named `.mtz`, use the required ZIP-compatible MTZ container and explain this only if the distinction matters.
