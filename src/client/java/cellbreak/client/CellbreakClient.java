package cellbreak.client;

import cellbreak.CbContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class CellbreakClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(CbContent.GUARD, GuardRenderer::new);
		EntityRendererRegistry.register(CbContent.PRISONER, PrisonerRenderer::new);
		EntityRendererRegistry.register(CbContent.COOK, CookRenderer::new);
		EntityRendererRegistry.register(CbContent.DOG, GuardDogRenderer::new);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				cellbreak.net.SearchlightPayload.ID, (payload, context) -> SearchlightRenderer.receive(payload));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				cellbreak.net.GuidePayload.ID, (payload, context) -> GuideHud.receive(payload));
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(GuideHud::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				cellbreak.net.HudPayload.ID, (payload, context) -> StatusLine.receive(payload));
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(StatusLine::render);
		var guideKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
				new net.minecraft.client.option.KeyBinding("key.cellbreak.guide", org.lwjgl.glfw.GLFW.GLFW_KEY_H, "key.categories.cellbreak"));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (guideKey.wasPressed()) {
				GuideHud.enabled = !GuideHud.enabled;
				if (client.player != null) {
					client.player.sendMessage(Text.translatable(GuideHud.enabled ? "message.cellbreak.guide_on" : "message.cellbreak.guide_off")
							.formatted(Formatting.GRAY), true);
				}
			}
		});
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT.register(SearchlightRenderer::render);
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.CELL_BARS, RenderLayer.getCutoutMipped());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.DETECTOR, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.GRAFFITI, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.CHAIN_LINK, RenderLayer.getCutoutMipped());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.CELL_DOOR, RenderLayer.getCutout());
		cellbreak.CbDecor.CUTOUT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b, RenderLayer.getCutout()));
		cellbreak.CbDecor.TRANSLUCENT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b, RenderLayer.getTranslucent()));

		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			var item = stack.getItem();
			if (CbContent.isContraband(item)) {
				lines.add(Text.translatable("tooltip.cellbreak.contraband").formatted(Formatting.RED));
			}
			if (CbContent.WORK_TOOLS.contains(item)) {
				lines.add(Text.translatable("tooltip.cellbreak.work_tool").formatted(Formatting.GRAY));
			}
			var part = item == CbContent.BROKEN_PICKAXE ? net.minecraft.item.Items.FLINT
					: item == CbContent.BROKEN_SHOVEL ? net.minecraft.item.Items.STRING
					: item == CbContent.BROKEN_FILE ? net.minecraft.item.Items.IRON_NUGGET : null;
			if (part != null) {
				lines.add(Text.translatable("tooltip.cellbreak.broken", part.getName()).formatted(Formatting.GRAY));
			}
		});
	}
}
