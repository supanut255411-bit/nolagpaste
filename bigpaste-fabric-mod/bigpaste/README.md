# BigPaste (Axiom companion, Fabric 1.21.11)

Streams giant `.litematic` files into a world with bounded memory.
Axiom is closed source and has no extension API, so this runs next to it as its own
Fabric mod (singleplayer / integrated server, or console on a dedicated server).

## Use
1. Put the file in `<minecraft>/schematics/`.
2. `/bigpaste start "Sakura.litematic"`            (pastes at your feet)
   `/bigpaste start "Sakura.litematic" 0 64 0`     (explicit lowest corner)
   `/bigpaste start "Sakura.litematic" 0 64 0 air` (also overwrite with air)
3. `/bigpaste status`, `/bigpaste cancel`
4. When it finishes, rejoin the world so your client gets the new chunks.
   Chunks are flagged to be relit when they next load from disk.

Use a void/superflat world for the area, otherwise vanilla generates every chunk first.

## Why memory stays flat
- Gzip NBT is parsed as a stream; the huge BlockStates array goes to `bigpaste-tmp/` on disk,
  never into the heap (needs free disk about equal to the unpacked array, ~1.2 GB for 2000x300x2000).
- Paste works one chunk-row at a time using a single reused buffer capped at 64 MB.
- Each chunk is loaded once, edited, marked dirty and left for vanilla to unload/save.
- If loaded chunks pile up faster than vanilla unloads them, the job pauses.
- Temp files are deleted on finish, cancel, failure, server stop, and on next startup after a crash.

## Not included yet
Entities, block-entity NBT (chests/signs get empty block entities), rotation/mirror, undo.

## Building
Generate a 1.21.11 project at https://fabricmc.net/develop (gets you the Gradle wrapper),
then copy `src/` and `gradle.properties` over it. Run `./gradlew build`.
Untested against the real game: if the compiler complains, the version-sensitive spots are
`resolveBlock`, `markNeedsSaving`, `setLightOn`, `Heightmap.populateHeightmaps`, `getLoadedChunkCount`.
