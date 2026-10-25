# Manual crafting dupe repro (no mod)

Proves the dupe without the test mod. Uses only two tiny datapacks and normal gameplay.
Do it in **survival** in a throwaway world.

1. Remove `megamcfixer-dupetest` from your mods folder (so the mod can't be involved).
2. Copy `dupe_repro_timer.zip` into `saves/<world>/datapacks/`, then run `/reload`.
3. Copy `dupe_repro_no_sticks.zip` into the same folder. **Don't** reload yet.
4. Get exactly 32 oak planks (for example `/give @s oak_planks 32`) and switch to survival: `/gamemode survival`.
5. Run `/schedule function dupe_repro:reload_now 15s`
6. Within 15 seconds, open a crafting table and put 16 planks in the top-left slot and
   16 in the slot below it. 4 sticks appear in the result.
7. Wait for the yellow `[repro] Reloading NOW` message (the stick recipe gets removed,
   but the sticks stay in the result slot), then take the sticks.
8. Look at the grid: each stack goes from 16 to **30**. Close the table: you have
   60 planks and 4 sticks. Relog to confirm they're real.

With fix #3 applied (`ResultSlot.getRemainingItems` uses `defaultCraftingReminder`),
step 8 shows 15 + 15 instead.

**Afterwards:** delete `dupe_repro_no_sticks.zip` (and the timer pack) and run `/reload`,
or sticks stay uncraftable in that world.
