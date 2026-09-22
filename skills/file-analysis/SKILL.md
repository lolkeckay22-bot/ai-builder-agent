---
name: file-analysis
description: Inspect attached files, detect their actual type, extract useful content, and feed complete verified inputs into another file or build task.
---
# File Analysis

Identify type from magic bytes and structure, not only the extension. Verify every chunk SHA-256, its index and size, then verify the reconstructed file SHA-256 and total size before parsing. Stop with a clear integrity error if any check differs. Never silently truncate an input, overwrite the original, or claim unsupported parsing.
