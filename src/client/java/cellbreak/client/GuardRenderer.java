package cellbreak.client;

import cellbreak.CbContent;
import cellbreak.entity.GuardEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

public class GuardRenderer extends BipedEntityRenderer<GuardEntity, PlayerEntityModel<GuardEntity>> {
	private static final Identifier[] TEXTURES = new Identifier[GuardEntity.VARIANTS];

	static {
		for (int i = 0; i < TEXTURES.length; i++) {
			TEXTURES[i] = CbContent.id("textures/entity/guard_" + i + ".png");
		}
	}

	public GuardRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);
	}

	@Override
	public Identifier getTexture(GuardEntity entity) {
		return TEXTURES[Math.floorMod(entity.getVariant(), TEXTURES.length)];
	}
}
