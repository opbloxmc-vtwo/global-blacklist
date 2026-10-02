# Global Blacklist

This repository contains the public network blacklist and the Spigot/Paper plugin that reads it. `blacklist.json` is the source of truth; proposed changes are submitted as pull requests and become active only after review and merge.

## Repository Contents

- `blacklist.json`: approved blacklist entries.
- `spigot/`: Spigot/Paper plugin source.

## Review Protection

Protect `main` with pull requests required, at least one approval required, and direct pushes/review bypass disabled. The plugin creates a branch and pull request for each proposed update; it never merges entries itself.

The blacklist is public. Do not put credentials or secrets in `blacklist.json` or other tracked files. Store the GitHub token only in the server's `plugins/BlacklistSpigot/config.yml`.

## Build

Requires Java 21 and Maven. From the repository root, run:

```powershell
mvn package
```

The shaded plugin jar is produced under `spigot/target/`.
