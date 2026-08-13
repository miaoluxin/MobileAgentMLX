# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities. Report them privately to the repository owner via GitHub's private vulnerability reporting (Security → Report a vulnerability), or by contacting the maintainers through the issue tracker with a clearly-marked *[SECURITY]* title.

Please include:

- The affected version (APK / commit)
- A description of the vulnerability and its impact
- Reproduction steps (as minimal as possible)
- Any suggested fix, if you have one

## Security posture

- **API keys**: stored on-device only, encrypted with the Android Keystore (alias `mlx_secrets`). Never logged, never transmitted except to the configured API endpoint.
- **Workspace isolation**: file tools are path-validated against the workspace root (`../` traversal and symlink escapes are rejected). In plan mode, write operations are engine-blocked (shell commands are whitelist-checked for read-only).
- **No cloud relay**: the app talks directly to the DeepSeek API from your device. There is no intermediary server.
- **Built-in environment**: the embedded Termux distribution is distributed under its own component licenses (see the app's product notes); it ships with no user data.

## Supported versions

Security fixes are applied to the latest release. There is no LTS line yet — please keep the app updated.
