package com.megamcfixer.dupetest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleplayer test for the crafting-result dupe in ResultSlot.getRemainingItems.
 *
 * Reproduces the real-world case: a recipe disappears (datapack + reload) while its
 * result is still showing in an open crafting table, then the player takes the result.
 * Buggy code: each grid stack roughly doubles (16 -> 30). Fixed code: each loses 1 (16 -> 15).
 */
public final class CraftingDupeTest {
   private static final Logger LOGGER = LoggerFactory.getLogger("megamcfixer-dupetest");
   private static final String PACK_FOLDER = "megamcfixer_dupe_test";
   private static final String PACK_ID = "file/" + PACK_FOLDER;
   private static final int PLANKS_PER_SLOT = 16;

   private CraftingDupeTest() {
   }

   /** Client thread: called by the button in the crafting screen. */
   public static void requestFromClient() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return;
      }

      IntegratedServer server = minecraft.getSingleplayerServer();
      if (server == null) {
         minecraft.gui.hud.getChat()
            .addClientSystemMessage(Component.literal("[DupeTest] Only works in singleplayer.").withStyle(ChatFormatting.RED));
         return;
      }

      UUID playerId = minecraft.player.getUUID();
      server.execute(() -> runOnServer(server, playerId));
   }

   /** Server thread. */
   private static void runOnServer(final MinecraftServer server, final UUID playerId) {
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null) {
         return;
      }

      if (!(player.containerMenu instanceof CraftingMenu menu)) {
         say(player, ChatFormatting.RED, "Open a crafting table first.");
         return;
      }

      Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_FOLDER);
      try {
         runTest(server, player, menu, packDir);
      } catch (Exception e) {
         LOGGER.error("Crafting dupe test failed", e);
         say(player, ChatFormatting.RED, "Test error: " + e);
         clearTestItems(menu);
      } finally {
         try {
            if (removePackAndRestoreRecipes(server, packDir)) {
               say(player, ChatFormatting.GRAY, "Cleanup: test datapack removed, recipes restored.");
            }
         } catch (Exception e) {
            LOGGER.error("Crafting dupe test cleanup failed", e);
            say(player, ChatFormatting.RED, "Cleanup failed! Delete " + packDir + " and run /reload. " + e);
         }
      }
   }

   private static void runTest(final MinecraftServer server, final ServerPlayer player, final CraftingMenu menu, final Path packDir) throws IOException {
      List<Slot> grid = menu.getInputGridSlots();
      Slot resultSlot = menu.getResultSlot();

      // 1. Move anything already in the grid or on the cursor back to the player.
      giveBack(player, menu.getCarried().copy());
      menu.setCarried(ItemStack.EMPTY);
      for (Slot slot : grid) {
         if (slot.hasItem()) {
            giveBack(player, slot.getItem().copy());
            slot.set(ItemStack.EMPTY);
         }
      }

      // The real items end up in your inventory at the end, so make sure they fit (no drops).
      if (countFreeInventorySlots(player) < 3) {
         say(player, ChatFormatting.RED, "Free up at least 3 inventory slots first, so the results can't fall on the ground.");
         return;
      }

      // Server-side count before the test (this is the server's own data, not what your screen shows).
      int inventoryPlanksBefore = countInventoryPlanks(player);

      // 2. Two stacks of planks on top of each other = the stick recipe.
      grid.get(0).set(new ItemStack(Items.OAK_PLANKS, PLANKS_PER_SLOT));
      grid.get(3).set(new ItemStack(Items.OAK_PLANKS, PLANKS_PER_SLOT));
      ItemStack shownResult = resultSlot.getItem().copy();
      Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(server, player, grid);
      if (shownResult.isEmpty() || recipe.isEmpty()) {
         say(player, ChatFormatting.RED, "Setup failed: planks did not produce a crafting result (is doLimitedCrafting on?).");
         clearTestItems(menu);
         return;
      }

      Identifier recipeId = recipe.get().id().identifier();
      say(player, ChatFormatting.GRAY, "Step 1: grid has 2x" + PLANKS_PER_SLOT + " planks, result shows " + describe(shownResult) + " (recipe " + recipeId + ").");

      // 3. Remove that recipe the way an admin would: datapack + reload, with the table still open.
      writeOverridePack(packDir, recipeId);
      PackRepository repository = server.getPackRepository();
      repository.reload();
      if (repository.getPack(PACK_ID) == null) {
         say(player, ChatFormatting.RED, "Setup failed: the test datapack was not detected.");
         clearTestItems(menu);
         return;
      }

      List<String> packs = new ArrayList<>(repository.getSelectedIds());
      if (!packs.contains(PACK_ID)) {
         packs.add(PACK_ID);
      }

      server.reloadResources(packs).join();
      boolean recipeStillExists = findRecipe(server, player, grid).isPresent();
      ItemStack staleResult = resultSlot.getItem().copy();
      say(player, ChatFormatting.GRAY, "Step 2: reloaded. Recipe still exists: " + recipeStillExists + ". Result slot shows: " + describe(staleResult) + ".");
      if (recipeStillExists) {
         say(player, ChatFormatting.RED, "Setup failed: the reload did not remove the recipe.");
         clearTestItems(menu);
         return;
      }

      if (staleResult.isEmpty()) {
         say(player, ChatFormatting.GREEN, "NOT REACHABLE: the result was cleared by the reload, so the dupe can't happen this way.");
         clearTestItems(menu);
         return;
      }

      // 4. Take the result exactly like a normal left-click on the result slot.
      int before = countGrid(grid);
      menu.clicked(resultSlot.index, 0, ContainerInput.PICKUP, player);
      ItemStack taken = menu.getCarried().copy();
      int after = countGrid(grid);
      menu.broadcastChanges();
      say(player, ChatFormatting.GRAY, "Step 3: took " + describe(taken) + ". Planks in grid (server-side): " + before + " -> " + after + ".");

      // 5. Prove it isn't a ghost item: move everything into your real inventory on the server,
      //    force a full resync to your client, and save the world to disk.
      int planksPutIn = PLANKS_PER_SLOT * 2;
      for (Slot slot : grid) {
         if (slot.hasItem()) {
            giveBack(player, slot.getItem().copy());
            slot.set(ItemStack.EMPTY);
         }
      }

      giveBack(player, menu.getCarried().copy());
      menu.setCarried(ItemStack.EMPTY);
      menu.sendAllDataToRemote();
      server.getPlayerList().saveAll();
      int planksGained = countInventoryPlanks(player) - inventoryPlanksBefore;
      say(player, ChatFormatting.GRAY, "Step 4: the test put " + planksPutIn + " planks in. Your inventory (server-side) now has " + planksGained + " more planks than before, plus " + describe(taken) + ". World saved.");

      if (planksGained > planksPutIn) {
         say(player, ChatFormatting.RED, "DUPE CONFIRMED (real, not ghost): " + planksPutIn + " planks in, " + planksGained + " planks back AND " + describe(taken) + ". Relog to double-check: ghost items vanish on relog, these won't.");
      } else if (taken.isEmpty()) {
         say(player, ChatFormatting.GREEN, "NO DUPE: the result could not be taken.");
      } else {
         say(player, ChatFormatting.GREEN, "NO DUPE: " + planksPutIn + " planks in, " + planksGained + " back plus " + describe(taken) + " (normal crafting cost).");
      }
   }

   private static int countFreeInventorySlots(final ServerPlayer player) {
      int free = 0;
      for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
         if (stack.isEmpty()) {
            free++;
         }
      }

      return free;
   }

   private static int countInventoryPlanks(final ServerPlayer player) {
      int total = 0;
      for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
         if (stack.is(Items.OAK_PLANKS)) {
            total += stack.getCount();
         }
      }

      return total;
   }

   private static Optional<RecipeHolder<CraftingRecipe>> findRecipe(final MinecraftServer server, final ServerPlayer player, final List<Slot> grid) {
      List<ItemStack> items = new ArrayList<>(grid.size());
      for (Slot slot : grid) {
         items.add(slot.getItem());
      }

      CraftingInput input = CraftingInput.of(3, 3, items);
      return server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, player.level());
   }

   private static void writeOverridePack(final Path packDir, final Identifier recipeId) throws IOException {
      PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
      String formatJson = "[" + format.major() + ", " + format.minor() + "]";
      String mcmeta = "{\n"
         + "  \"pack\": {\n"
         + "    \"description\": \"megamcfixer crafting dupe test - safe to delete\",\n"
         + "    \"min_format\": " + formatJson + ",\n"
         + "    \"max_format\": " + formatJson + "\n"
         + "  }\n"
         + "}\n";
      // Same id as the real recipe, but needs a barrier, so the planks no longer match anything.
      String recipeJson = "{\n"
         + "  \"type\": \"minecraft:crafting_shapeless\",\n"
         + "  \"ingredients\": [\"minecraft:barrier\"],\n"
         + "  \"result\": {\"id\": \"minecraft:barrier\"}\n"
         + "}\n";

      Path recipeFile = packDir.resolve("data").resolve(recipeId.getNamespace()).resolve("recipe").resolve(recipeId.getPath() + ".json");
      Files.createDirectories(recipeFile.getParent());
      Files.writeString(packDir.resolve("pack.mcmeta"), mcmeta, StandardCharsets.UTF_8);
      Files.writeString(recipeFile, recipeJson, StandardCharsets.UTF_8);
   }

   /** Returns true if anything had to be cleaned up. */
   private static boolean removePackAndRestoreRecipes(final MinecraftServer server, final Path packDir) throws IOException {
      boolean existed = Files.exists(packDir);
      if (existed) {
         try (Stream<Path> files = Files.walk(packDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
               try {
                  Files.delete(path);
               } catch (IOException e) {
                  throw new UncheckedIOException(e);
               }
            });
         }
      }

      PackRepository repository = server.getPackRepository();
      boolean wasSelected = repository.getSelectedIds().contains(PACK_ID);
      repository.reload();
      if (wasSelected) {
         server.reloadResources(new ArrayList<>(repository.getSelectedIds())).join();
      }

      return existed || wasSelected;
   }

   private static void clearTestItems(final CraftingMenu menu) {
      for (Slot slot : menu.getInputGridSlots()) {
         slot.set(ItemStack.EMPTY);
      }

      menu.setCarried(ItemStack.EMPTY);
      menu.broadcastChanges();
   }

   private static int countGrid(final List<Slot> grid) {
      int total = 0;
      for (Slot slot : grid) {
         if (slot.getItem().is(Items.OAK_PLANKS)) {
            total += slot.getItem().getCount();
         }
      }

      return total;
   }

   private static void giveBack(final ServerPlayer player, final ItemStack stack) {
      if (!stack.isEmpty()) {
         player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
      }
   }

   private static String describe(final ItemStack stack) {
      return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
   }

   private static void say(final ServerPlayer player, final ChatFormatting color, final String message) {
      player.sendSystemMessage(Component.literal("[DupeTest] " + message).withStyle(color));
   }
}
