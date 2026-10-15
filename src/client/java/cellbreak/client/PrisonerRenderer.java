package cellbreak.client;

import cellbreak.CbContent;
import cellbreak.entity.PrisonerNpcEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

public class PrisonerRenderer extends BipedEntityRenderer<PrisonerNpcEntity, PlayerEntityModel<PrisonerNpcEntity>> {
	private static final Identifier[] TEXTURES = new Identifier[PrisonerNpcEntity.VARIANTS];
	private static final Identifier[] TRADER_TEXTURES = new Identifier[PrisonerNpcEntity.VARIANTS];

	static {
		for (int i = 0; i < TEXTURES.length; i++) {
			TEXTURES[i] = CbContent.id("textures/entity/prisoner_" + i + ".png");
			TRADER_TEXTURES[i] = CbContent.id("textures/entity/prisoner_trader_" + i + ".png");
		}
	}

	public PrisonerRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);
	}

	@Override
	public Identifier getTexture(PrisonerNpcEntity entity) {
		int v = Math.floorMod(entity.getVariant(), TEXTURES.length);
		return entity.isTrader() ? TRADER_TEXTURES[v] : TEXTURES[v];
	}
}
