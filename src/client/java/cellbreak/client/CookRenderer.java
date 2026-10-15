package cellbreak.client;

import cellbreak.CbContent;
import cellbreak.entity.CookEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

public class CookRenderer extends BipedEntityRenderer<CookEntity, PlayerEntityModel<CookEntity>> {
	private static final Identifier GLOOMY = CbContent.id("textures/entity/cook_0.png");
	private static final Identifier CHEERFUL = CbContent.id("textures/entity/cook_1.png");

	public CookRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);
	}

	@Override
	public Identifier getTexture(CookEntity entity) {
		return entity.isCheerful() ? CHEERFUL : GLOOMY;
	}
}
