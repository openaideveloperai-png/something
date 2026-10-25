# megamcfixer: bugs found and fixes (Minecraft 26.3 code)

Each entry gives the file and method, what goes wrong, and replacement code.
Method and class names are the ones in your source. 26.x ships unobfuscated,
so the same names also work as Mixin targets if you ship these as a mod.

Severity key: **CRASH** = game/server goes down, **DUPE** = items created,
**EXPLOIT** = cheat or abuse, **LOSS** = items deleted, **KICK** = client gets disconnected.

---

## Server-side (applies to LAN worlds hosted from your client, or any server running this code)

### 1. Vertical noclip ("vclip") through floors and ceilings — EXPLOIT
`server/network/ServerGamePacketListenerImpl.java`, in `handlePlayerPositionChange` (called by `handleMovePlayer`) **and** in `handleMoveVehicle`.

`yDist > -0.5 || yDist < 0.5` is true for every number, so the vertical error is always
reset to 0. A hacked client can move straight down or up through solid blocks (up to the
speed limit, about 10 blocks per packet). The vehicle copy lets boats and horses do the same.

```java
// BEFORE (both places)
if (yDist > -0.5 || yDist < 0.5) {
   yDist = 0.0;
}

// AFTER (both places)
if (yDist > -0.5 && yDist < 0.5) {
   yDist = 0.0;
}
```
Test this on your server: vertical position errors of 0.5 blocks or more will now be
pulled back, the same as horizontal ones already are.

### 2. Anyone can log in as the LAN host and shut the world down — EXPLOIT
`server/network/ServerLoginPacketListenerImpl.java`, `handleHello`.

When a world is open to LAN, a connection that sends the host's username gets the host's
profile **before any authentication**. Logging in kicks the real host, and when the host
leaves the integrated server stops, so anyone who can reach your LAN port can close your world.
Owner checks are name-based (`IntegratedServer.isSingleplayerOwner`), so the impersonator is
also treated as the owner.

```java
@Override
public void handleHello(final ServerboundHelloPacket packet) {
   Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet", new Object[0]);
   Validate.validState(StringUtil.isValidPlayerName(packet.name()), "Invalid characters in username", new Object[0]);
   this.requestedUsername = packet.name();
   GameProfile singleplayerProfile = this.server.getSingleplayerProfile();
   if (singleplayerProfile != null && this.requestedUsername.equalsIgnoreCase(singleplayerProfile.name())) {
      // FIX: only the host's own in-memory connection may use the host profile
      if (!this.connection.isMemoryConnection()) {
         this.disconnect(PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
         return;
      }
      this.startClientVerification(singleplayerProfile);
   } else {
      if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
         this.state = ServerLoginPacketListenerImpl.State.KEY;
         this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, true));
      } else {
         this.startClientVerification(UUIDUtil.createOfflineProfile(this.requestedUsername));
      }
   }
}
```
(`PlayerList` is already imported in that file.)

### 3. Crafting result dupe when recipes are reloaded — DUPE
`world/inventory/ResultSlot.java`, `getRemainingItems`.

If no recipe matches when you take the result (for example `/reload` removed it while the
result was showing), the fallback `copyAllInputItems` returns the **same item objects** that
are in the grid as "leftovers". In `onTake` each grid stack then grows by itself:
64 sticks become 126 and you keep the crafted item.

```java
private NonNullList<ItemStack> getRemainingItems(final CraftingInput input, final Level level) {
   return level instanceof ServerLevel serverLevel
      ? serverLevel.recipeAccess()
         .getRecipeFor(RecipeType.CRAFTING, input, serverLevel)
         .map(recipe -> recipe.value().getRemainingItems(input))
         .orElseGet(() -> CraftingRecipe.defaultCraftingReminder(input)) // FIX: was copyAllInputItems(input)
      : CraftingRecipe.defaultCraftingReminder(input);
}
```
Then delete the unused `copyAllInputItems` method.

### 4. Unlimited banner layers via loom button packet — EXPLOIT (lag banners)
`world/inventory/LoomMenu.java`, `setupResultSlot`.

The 6-layer limit is only checked in `slotsChanged`. `clickMenuButton` calls `setupResultSlot`
directly, so a hacked client can keep clicking a pattern button and add a 7th, 8th, ...
100th layer. Banners like that lag every player who renders them.

```java
private void setupResultSlot(final Holder<BannerPattern> pattern) {
   ItemStack bannerStack = this.bannerSlot.getItem();
   ItemStack dyeStack = this.dyeSlot.getItem();
   ItemStack result = ItemStack.EMPTY;
   // FIX: enforce the layer limit here too, not only in slotsChanged
   if (bannerStack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().size() >= 6) {
      this.resultSlot.set(ItemStack.EMPTY);
      return;
   }
   if (!bannerStack.isEmpty() && !dyeStack.isEmpty()) {
      DyeColor patternColor = dyeStack.get(DataComponents.DYE);
      if (patternColor != null) {
         result = bannerStack.copyWithCount(1);
         result.update(
            DataComponents.BANNER_PATTERNS,
            BannerPatternLayers.EMPTY,
            layers -> new BannerPatternLayers.Builder().addAll(layers).add(pattern, patternColor).build()
         );
      }
   }

   if (!ItemStack.matches(result, this.resultSlot.getItem())) {
      this.resultSlot.set(result);
   }
}
```

### 5. Log-flood lag from negative slot numbers — EXPLOIT (server lag)
`world/inventory/AbstractContainerMenu.java`, `isValidSlotIndex` and `doClick`.

`isValidSlotIndex` accepts **every** negative number (`-5 < slots.size()` is true).
A click packet with a hotbar swap on slot `-1`, or a drag on slot `-5`, throws inside
`doClick`. Every throw is logged as a full ERROR stack trace, so spamming these floods the
log and lags the server.

```java
public boolean isValidSlotIndex(final int slotIndex) {
   return slotIndex == -1 || slotIndex == -999 || slotIndex >= 0 && slotIndex < this.slots.size();
}
```
And in `doClick`:
```java
} else if (this.quickcraftStatus == 1) {
   if (slotIndex < 0) {              // FIX
      this.resetQuickCraft();
      return;
   }
   Slot slot = this.slots.get(slotIndex);
   ...

// FIX: add "slotIndex >= 0 &&" to the SWAP branch
} else if (containerInput == ContainerInput.SWAP && slotIndex >= 0 && (buttonNum >= 0 && buttonNum < 9 || buttonNum == 40)) {
```

### 6. Anvil deletes part of a renamed stack on shift-click — LOSS
`world/inventory/ItemCombinerMenu.java`, `quickMoveStack`.

Rename a stack of 64 and shift-click the result with room for only part of it: some items
move, `onTake` still charges XP and clears the input, then the result slot is recalculated
as empty and the rest is destroyed.

Only allow shift-clicking the result when the whole stack fits:
```java
if (slotIndex == this.getResultSlot()) {
   if (!this.canFitFully(stack, inventorySlotStart, useRowSlotEnd)) {   // FIX
      return ItemStack.EMPTY;
   }
   if (!this.moveItemStackTo(stack, inventorySlotStart, useRowSlotEnd, true)) {
      return ItemStack.EMPTY;
   }
   slot.onQuickCraft(stack, clicked);
} else if ...
```
Add this helper to `ItemCombinerMenu`:
```java
private boolean canFitFully(final ItemStack stack, final int start, final int end) {
   int remaining = stack.getCount();
   for (int i = start; i < end && remaining > 0; i++) {
      Slot target = this.slots.get(i);
      ItemStack inSlot = target.getItem();
      if (inSlot.isEmpty()) {
         if (target.mayPlace(stack)) {
            remaining -= target.getMaxStackSize(stack);
         }
      } else if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
         remaining -= Math.max(0, target.getMaxStackSize(inSlot) - inSlot.getCount());
      }
   }
   return remaining <= 0;
}
```

### 7. Recipe book deletes furnace output when your inventory is full — LOSS
`recipebook/ServerPlaceRecipe.java`, `clearGrid`.

Furnaces clear both the input **and the output slot** when you click the recipe book.
Survival only checks that the *input* fits back in your inventory. Whatever output doesn't
fit is put back into the slot, and then `clearCraftingContent()` wipes the slot.

```java
private void clearGrid() {
   for (Slot slot : this.slotsToClear) {
      ItemStack itemStackCopy = slot.getItem().copy();
      this.inventory.placeItemBackInInventory(itemStackCopy, false, Prediction.SERVER_ONLY);
      if (!itemStackCopy.isEmpty()) {
         // FIX: drop what doesn't fit instead of deleting it
         this.inventory.player.drop(itemStackCopy, false, Prediction.SERVER_ONLY);
      }
      slot.set(ItemStack.EMPTY);
   }

   this.menu.clearCraftingContent();
}
```

---

## Client-side anti-crasher fixes (malicious server -> your client)

### 8. Explosion packets can crash the whole game — CRASH
`client/multiplayer/ClientExplosionTracker.java`, `track`.

`blockCount` comes straight from the server. Two explosions in one tick with
`blockCount = Integer.MAX_VALUE` make `WeightedRandom.getTotalWeight` throw. That happens in
`ClientLevel.tick`, outside packet handling, so it is a **real game crash** (crash screen),
not just a disconnect. Works with the default particle setting.

```java
private static final int MAX_BLOCKS_PER_EXPLOSION = 4096;
private static final int MAX_EXPLOSIONS_PER_TICK = 256;

public void track(final Vec3 center, final float radius, final int blockCount, final WeightedList<ExplosionParticleInfo> blockParticles) {
   if (!blockParticles.isEmpty()
      && blockCount > 0
      && Float.isFinite(radius)
      && this.explosions.size() < MAX_EXPLOSIONS_PER_TICK) {
      this.explosions.add(new ClientExplosionTracker.ExplosionInfo(center, radius, Math.min(blockCount, MAX_BLOCKS_PER_EXPLOSION), blockParticles));
   }
}
```

### 9. Compressed-packet memory bomb — CRASH (out of memory)
`network/CompressionDecoder.java`, `decode`.

The client turns on compression with `validateDecompressed = false`
(`ClientHandshakePacketListenerImpl.handleCompression`), so the size limit is skipped.
A server can claim a decompressed size near 2 GB, and the client allocates that much direct
memory for **every packet**. The server's own encoder already refuses anything over 8 MiB,
so applying the limit on the client too can't break real servers.

```java
protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
   int uncompressedLength = VarInt.read(in);
   if (uncompressedLength == 0) {
      out.add(in.readBytes(in.readableBytes()));
   } else {
      // FIX: always enforce the protocol maximum, client included
      if (uncompressedLength < 0 || uncompressedLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
         throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is outside 0.." + MAXIMUM_UNCOMPRESSED_LENGTH);
      }

      if (this.validateDecompressed && uncompressedLength < this.threshold) {
         throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is below server threshold of " + this.threshold);
      }

      this.setupInflaterInput(in);
      ByteBuf output = this.inflate(ctx, uncompressedLength);
      this.inflater.reset();
      out.add(output);
   }
}
```

### 10. "Wake up" animation packet on a non-player — KICK
`client/multiplayer/ClientPacketListener.java`, `handleAnimate`.

Action 0 does `(Player)entity` with no check, so a server can send it for a zombie, boat,
etc. and throw `ClassCastException`.

```java
if (packet.getAction() == 0) {
   if (entity instanceof Player player) {   // FIX
      player.stopSleepInBed(false, false);
   }
} else if ...
```

### 11. Item pickup packet on a non-living collector — KICK (you already found this one)
`ClientPacketListener.handleTakeItemEntity`:
```java
Entity collector = this.level.getEntity(packet.getPlayerId());
LivingEntity to = collector instanceof LivingEntity living ? living : null;
```

### 12. Particle packet freeze — FREEZE (you already found this one)
`ClientPacketListener.handleParticleEvent`: cap `packet.count()`.
Mixin version (all three `count()` calls in the method are redirected, which is what you want):
```java
@Redirect(
   method = "handleParticleEvent",
   at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket;count()I")
)
private int megamcfixer$capParticleCount(ClientboundLevelParticlesPacket packet) {
   return Math.min(packet.count(), 16384);
}
```

### 13. Out-of-range slot or data ids from server — KICK
`world/inventory/AbstractContainerMenu.java`. `handleContainerSetSlot` and
`handleContainerSetData` pass server numbers straight into list lookups.

```java
public void setItem(final int slot, final int stateId, final ItemStack itemStack) {
   if (slot < 0 || slot >= this.slots.size()) {
      return; // FIX
   }
   this.getSlot(slot).set(itemStack);
   this.stateId = stateId;
}

public void setData(final int id, final int value) {
   if (id < 0 || id >= this.dataSlots.size()) {
      return; // FIX
   }
   this.dataSlots.get(id).set(value);
}

public void setRemoteSlot(final int slot, final ItemStack itemStack) {
   if (slot < 0 || slot >= this.remoteSlots.size()) {
      return; // FIX
   }
   this.remoteSlots.get(slot).force(itemStack);
}
```

### 14. Bad entity data from server — KICK
`network/syncher/SynchedEntityData.java`, `assignValues`. An unknown data id
(array index) or a mismatched data type throws and disconnects you. Skip bad entries instead:

```java
public void assignValues(final List<SynchedEntityData.DataValue<?>> items) {
   List<SynchedEntityData.DataValue<?>> applied = new ArrayList<>(items.size());
   for (SynchedEntityData.DataValue<?> item : items) {
      if (item.id < 0 || item.id >= this.itemsById.length) {
         continue; // FIX: unknown id
      }
      SynchedEntityData.DataItem<?> dataItem = this.itemsById[item.id];
      if (dataItem == null || !Objects.equals(item.serializer(), dataItem.accessor.serializer())) {
         continue; // FIX: wrong type
      }
      this.assignValue(dataItem, item);
      this.entity.onSyncedDataUpdated(dataItem.getAccessor());
      applied.add(item);
   }

   this.entity.onSyncedDataUpdated(applied);
}
```

### 15. Map patch outside the 128x128 map — KICK
`world/level/saveddata/maps/MapItemSavedData.java`, `MapPatch.applyToMap`. Patch position
and size go up to 255 and the colour array length isn't checked.

```java
public void applyToMap(final MapItemSavedData map) {
   for (int x = 0; x < this.width; x++) {
      for (int y = 0; y < this.height; y++) {
         int mapX = this.startX + x;
         int mapY = this.startY + y;
         int index = x + y * this.width;
         if (mapX < 128 && mapY < 128 && index < this.mapColors.length) { // FIX
            map.setColor(mapX, mapY, this.mapColors[index]);
         }
      }
   }
}
```

---

## Checked and found OK
Container click logic (pickup/swap/drag/throw/pick-all), `Slot`, `moveItemStackTo`,
shift-click in chest/hopper/shulker/dispenser/horse/crafting/inventory/furnace/brewing/
grindstone/stonecutter/enchanting/beacon/lectern/cartography/crafter, villager trading
(`MerchantResultSlot` + `satisfiedBy`/`take`), bundles, shelf block, item entity merge and
pickup, `Inventory.add`, chest minecart/boat drops, creative slot packet, book/sign/rename
limits, tab-complete rate limit, NBT size limits, legacy and status ping, keep-alive.
