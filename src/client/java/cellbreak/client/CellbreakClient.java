package cellbreak.client;

import cellbreak.CbContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

public class CellbreakClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(CbContent.GUARD, GuardRenderer::new);
		BlockRenderLayerMap.INSTANCE.putBlock(CbContent.CELL_BARS, RenderLayer.getCutoutMipped());
	}
}
