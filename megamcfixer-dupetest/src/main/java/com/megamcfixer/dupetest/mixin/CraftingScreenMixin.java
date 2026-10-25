package com.megamcfixer.dupetest.mixin;

import com.megamcfixer.dupetest.CraftingDupeTest;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Dupe test" button above the crafting table GUI. */
@Mixin(CraftingScreen.class)
public abstract class CraftingScreenMixin extends AbstractRecipeBookScreen<CraftingMenu> {
   private CraftingScreenMixin(final CraftingMenu menu, final RecipeBookComponent<?> recipeBook, final Inventory inventory, final Component title) {
      super(menu, recipeBook, inventory, title);
   }

   @Inject(method = "init", at = @At("TAIL"))
   private void megamcfixer$addDupeTestButton(final CallbackInfo ci) {
      this.addRenderableWidget(
         Button.builder(Component.literal("Dupe test"), button -> CraftingDupeTest.requestFromClient())
            .bounds(this.leftPos + this.imageWidth - 70, this.topPos - 22, 70, 20)
            .build()
      );
   }
}
