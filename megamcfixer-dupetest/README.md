# megamcfixer crafting dupe test (Fabric, client, singleplayer)

Adds a **Dupe test** button above the crafting table. Pressing it checks the
`ResultSlot.getRemainingItems` dupe for real, on the singleplayer server.

## What the button does
1. Moves anything already in the crafting grid or on your cursor into your inventory.
2. Puts 2 stacks of 16 oak planks in the grid (the stick recipe). The result shows sticks.
3. Writes a temporary datapack that replaces the stick recipe with one needing a barrier,
   and reloads, exactly like an admin running `/reload`, while the table stays open.
4. Takes the result like a normal left-click.
5. Moves everything into your real inventory **on the server**, forces a full resync to
   your client, and saves the world.
6. Deletes the datapack and reloads again, so the stick recipe is back.

All counts are read from the server's own data, not from your screen, so a ghost item
can't fool it. To double-check, relog: ghost items vanish on relog, real ones stay.

## Reading the result (in chat)
- **Bug present:** 32 planks in, **60** planks back, plus 4 sticks -> `DUPE CONFIRMED`
- **Bug fixed:** 32 planks in, **30** planks back, plus 4 sticks -> `NO DUPE`

## Use
- Singleplayer only, in a test world. Have at least 3 free inventory slots.
- `doLimitedCrafting` must be off (the default).
- If the game crashes mid-test, delete `<world>/datapacks/megamcfixer_dupe_test` and run `/reload`.

## Prebuilt jar
`dist/megamcfixer-dupetest-1.0.0.jar` is ready to use: drop it in your `mods` folder
(Fabric Loader 0.20.1, Minecraft 26.3). It was compiled against your 26.3 classes and
Fabric's Mixin 0.17.5. No Fabric API needed.

## Build it yourself
1. `gradle.properties` already has loader 0.20.1 and Loom 1.19-SNAPSHOT.
2. `gradle build` (or copy the Gradle wrapper from the official template and run `./gradlew build`).
3. The mod jar is in `build/libs/`. Drop it in your `mods` folder.

If the build files give you trouble, generate the official template for 26.3 at
https://fabricmc.net/develop/template/ and copy this project's `src/` folder into it.
If that template's mixins json uses a different `compatibilityLevel`, use its value.
