# Luna Auth — Beta

Full source is kept here, including the MIT-licensed EasyAuth fork. No Discord bot token belongs in a Minecraft server.

## Support

| Build | Minecraft | Dependencies | Status |
| --- | --- | --- | --- |
| Luna Auth (Fabric) | 1.20–1.20.1 | Fabric Loader 0.16.10+, Fabric API | Beta; limited live testing |
| Luna Bridge (Paper) | Compiled against 1.20.1 API; newer versions require testing | AuthMe with v3 API, Java 17+ | Build-tested; Paper live trial pending |
| Forge / NeoForge / Bedrock / other Fabric versions | — | — | Not supported by these JARs |

Clients need no Luna mod. Fabric modpacks may use Luna Auth if their Minecraft version matches and no other authentication mod conflicts. This is not a universal JAR. The Fabric build uses EasyAuth ID `easyauth`; never install it beside EasyAuth or the old Violet Auth JAR.

## Installation and pairing

1. Back up the Minecraft world, authentication database and configuration. Test with a separate copy first.
2. Fabric: replace the EasyAuth/Violet Auth JAR with Luna Auth in `mods/`, preserving `config/EasyAuth` and the existing database. Install Fabric API. This fork is based on upstream EasyAuth 3.2.1; custom Violet Discord links must be verified again with Luna.
3. Paper: install AuthMe and Luna Bridge in `plugins/`. Add `/link` and `/luna` to AuthMe's `settings.restrictions.allowCommands` so private verification and reset commands can run before login. Restart after changing AuthMe configuration. Paper does not automatically provide Fabric's mixed online/offline handshake; use your existing supported AuthMe/premium authentication setup. Offline-mode accounts are labelled unverified rather than guessed to be official.
4. Start the Minecraft server. A local `luna-bridge.json` is created: Fabric in `config/`, Paper in `plugins/LunaBridge/`. Its default `panelUrl` points at Luna. Change it only to your trusted panel's HTTPS origin.
5. In Luna's community Minecraft page, enable the module, add the server, save and generate a pairing key.
6. In the **server console**, run `luna pair <key>`. The key expires after ten minutes and works once. Never paste it into public Minecraft or Discord chat. The console may retain command history; keep console access restricted.
7. Refresh the panel. Heartbeats run every 15 seconds, with player batches of up to 100. Accounts appear as players join; historical authentication databases are not imported wholesale.

No separate public API port is required. The mod/plugin makes outbound HTTPS requests; Luna does not connect to a user-entered address. The address and game port in the panel are for display only.

## Linking

- Log in/register locally, then `/link <Discord ID>`.
- Luna checks membership in the configured Discord community. Non-members receive a one-use invite created by the bot when permitted, otherwise the configured fallback invite.
- The Discord owner receives a private six-digit code, valid for ten minutes with at most five incorrect attempts. In Minecraft use `/link confirm <code>`.
- Linking binds the Discord account to this Minecraft UUID **within this paired server**. Existing links cannot be changed to another Discord owner without an administrator unlinking first.
- When offline password login is disabled, each new Minecraft session must verify a fresh Discord code, even if the account is already linked. The connector does not trust an offline username as proof of ownership.
- Required linking blocks offline players’ ordinary movement, chat and game actions until verified. Official verified accounts can play without Discord linking by default. Enable “Also require Discord linking for official accounts” if you want to gate those accounts too. Local login/registration and private link/reset commands remain available.

## Account actions

Only the Discord community owner or an administrator can queue actions. The connector must have a recent heartbeat. Actions expire after ten minutes and report receipts back to the panel; details also appear in Luna's administrative audit history.

- **Request password reset:** DMs the verified linked Discord owner a private reset code. In Minecraft use `/luna reset <code> <new-password>`. Reset codes are account-bound and single use; passwords must contain 8–128 characters. Only a SHA-256 hash of the reset code is sent to/stored by the connector. Passwords never go to Luna.
- **Force password reset:** replaces the password with an unknown random value and clears local sessions. Send a reset request afterwards. This does not remove account ownership protection by opening unauthenticated registration.
- **Clear remembered login:** invalidates Fabric's remembered IP/session. Paper blocks session restoration for that account; it stays on fresh-login mode until the UUID is removed from the plugin's local `clearedSessions` setting. Official verified authentication is unaffected.
- **Unlink Discord:** removes the verified mapping and disconnects the Minecraft player.
- **Delete authentication record:** removes the local login record and disconnects the player. This intentionally allows re-registration. It does **not** delete world/inventory data or the Discord mapping; unlink separately if needed.

Paper receipts mean an action was handed to AuthMe's API. AuthMe manages asynchronous persistence and logs any provider errors locally. Verify an account action before relying on its result during the trial.

## Stored data and outages

Minecraft keeps password hashes, login IPs, local session state and `luna-bridge.json`. Luna stores player UUID/name, official/unverified flag, registration status, Discord mapping, heartbeat and action receipts. It stores only hashes of connector credentials/pairing keys. Protect the local connector file: it contains the server credential, cached player/Discord links and reset-code hashes. On POSIX its writes use owner-only permissions. On Windows restrict the server folder to its operator.

Discord verification/reset delivery needs the bot and panel online. Existing password authentication stays local during outages; cached required-link settings still apply. Passwordless offline players cannot start a fresh verified session while the panel is unavailable. Changes reach the connector at its next successful heartbeat. Disable the module or disconnect the connector to reject further API requests; this does not erase the local authentication database.

Destructive jobs are recorded locally before dispatch. A server restart during a job produces an uncertain/failed receipt instead of replaying a possibly completed destructive operation. Check locally before retrying. Receipts are retained for 30 days. Private reset challenges persist across connector restarts until expiry. The account roster is capped at 5,000 per paired server.

## Build from source

Install a JDK (Java 21 used for these builds). The output targets Java 17. The Gradle wrapper is included under `fabric/` and can build both projects.

Windows, from this directory:

```powershell
./fabric/gradlew.bat -p fabric --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2G' build
./fabric/gradlew.bat -p paper --no-daemon --max-workers=1 build
```

Linux/macOS:

```sh
sh fabric/gradlew -p fabric --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2G' build
sh fabric/gradlew -p paper --no-daemon --max-workers=1 build
```

Outputs: `fabric/build/libs/easyauth-mc1.20-3.2.1-luna.3.jar` and `paper/build/libs/luna-bridge-paper-0.1.2.jar`. `common/` is compiled into both builds. The Fabric sources JAR is also generated. The downloadable source ZIP contains these sources, wrappers, licences and this guide; it excludes credentials, runtime databases and build caches.

Upstream EasyAuth: https://github.com/NikitaCartes/EasyAuth/tree/fabric-1.20 (MIT licence preserved in `fabric/LICENSE`). Paper uses AuthMe's public v3 API; AuthMe itself is installed separately and is not bundled or relicensed.


## Source repository

The standalone auth project is maintained at https://github.com/koko-0012/luna-auth. It is private during cleanup and review; no public source link should be advertised until its visibility changes. The main bot/dashboard remains a separate private project.

This repository contains only Minecraft connector code. Discord credentials, panel data, player databases, pairing files, caches and generated JARs are excluded. GitHub Actions builds both projects and keeps private build artifacts; it does not publish releases automatically.

The EasyAuth mod ID, Java packages, configuration location and migration names are retained for compatibility. Changing them as branding would risk existing authentication data and mod integrations. See THIRD_PARTY_NOTICES.md for the source attribution.
