# Global Blacklist

Spigot/Paper network blacklist. GitHub is the sole source of truth. New entries are proposed as pull requests and become active only after review and merge.

## Requirements

- Java 21 and Spigot/Paper 1.21+
- Floodgate installed for Bedrock XUID support
- A GitHub repository with a protected `main` branch

## GitHub Setup

The configured repository is `opbloxmc-vtwo/global-blacklist`. It is currently empty, so initialize it before using add commands:

1. Create `blacklist.json` on `main` with this initial content:

	 ```json
	 {
		 "entries": []
	 }
	 ```

2. Protect `main`: require pull requests, require at least one approval, and prevent direct pushes or review-rule bypasses.
3. Create a fine-grained GitHub token for this repository with **Contents: read and write** and **Pull requests: read and write**.
4. Start the plugin once, then put the token in `plugins/BlacklistSpigot/config.yml` under `github.token`. Do not commit or share that token.
5. Restart the server. Public repositories can be read without a token, but creating review requests always requires one.

The JSON file contains `platform`, `uuid`, `xuid`, `username`, `reason`, `addedBy`, and `addedAt` fields for each entry. Entries become active after merge and are refreshed every 90 seconds by default.

The plugin fails closed for new logins if neither GitHub nor a previously saved local cache can provide the list. Initialize the repository before deploying the plugin; otherwise players will be asked to retry until the list is available.

## Commands

- `/blacklist add <player[,player2,...]> [reason]` resolves targets and submits a pull request.
- `/blacklist add --dry-run <player[,player2,...]> [reason]` previews the targets and returns a short-lived confirmation code.
- `/blacklist confirm <code>` submits that preview as a pull request.
- `/blacklist check <player|uuid:<uuid>|offline:<name>|xuid:<xuid>>` looks up one identity.
- `/blacklist list [page]` shows entries from the GitHub repository.

`blacklist.add` controls add and confirm. `blacklist.view` controls check and list. Both default to operators.

## Identity Formats

- Online Java players use their current server UUID. Offline Java names are resolved through Mojang to handle username changes; a confirmed Mojang “not found” uses the cracked/offline UUID.
- Use `uuid:<uuid>` to target a stable Java UUID directly.
- Use `offline:<name>` to force the offline-mode UUID in a mixed network.
- Online Bedrock players are identified by Floodgate XUID. Use `xuid:<digits>` when targeting a Bedrock player who is offline.

Existing database-only entries are no longer enforced or migrated. Before replacing the old plugin, add any bans you need to retain to the GitHub list through reviewed pull requests.

## Permissions

- `blacklist.add`: add players and confirm pull requests.
- `blacklist.view`: check identities and view pages.
# 5\. Restart the server.


