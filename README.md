# Axiom Paper Plugin

Serverside component for Axiom

(todo: better readme)

## Download

GitHub Actions builds a plugin JAR on every push. Grab it from:

- **Releases** on this repository — `AxiomPaper.jar` (updated on each commit)
- The **Artifacts** section on each **Actions** run

Drop `AxiomPaper.jar` in your server `plugins` folder.

Official Modrinth builds: https://modrinth.com/plugin/axiom-paper-plugin/

## Platform support

This plugin supports Paper, Folia, and Folia forks such as CanvasMC (`folia-supported: true`). World edits, chunk requests, and entity changes are scheduled on the region that owns the affected location.

The latest public Axiom client for Minecraft 26.2 is **5.5.0** (API 9). This plugin accepts both API 9 and API 10 so that client can join, and intercepts large plugin messages so they are not rejected by Minecraft's 32KB custom-payload limit.

## FAQ

**Axiom works in singleplayer but not when I connect to a multiplayer server running the Axiom Paper Plugin. What gives?**

First, the player must be an op on the server. If the player does not have op permissions, run `/op <playername>`. This player must then disconnect from the server and reconnect.

If you're using an alternative solution for permission management, you must give players the `axiom.default` permission.

If players continue to have issues, they can run the `/whynoaxiom` command for more information.
