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
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.CELL_BARS, RenderLayer.getCutoutMipped());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.DETECTOR, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.GRAFFITI, RenderLayer.getCutout());

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
