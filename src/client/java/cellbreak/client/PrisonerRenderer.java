package cellbreak.client;

import cellbreak.CbContent;
import cellbreak.entity.PrisonerNpcEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

public class PrisonerRenderer extends BipedEntityRenderer<PrisonerNpcEntity, PlayerEntityModel<PrisonerNpcEntity>> {
	private static final Identifier TEXTURE = CbContent.id("textures/entity/prisoner.png");
	private static final Identifier TRADER_TEXTURE = CbContent.id("textures/entity/prisoner_trader.png");

	public PrisonerRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);
	}

	@Override
	public Identifier getTexture(PrisonerNpcEntity entity) {
		return entity.isTrader() ? TRADER_TEXTURE : TEXTURE;
	}
}
