# Security

- No API keys or GitHub tokens are compiled into the Android app.
- CI permissions are read-only except artifact upload handled by GitHub Actions.
- User files must be copied into an isolated task workspace before edits.
- Backends must enforce timeouts, file-size limits, path containment and iteration limits.
- Logs and artifacts must redact secrets.
