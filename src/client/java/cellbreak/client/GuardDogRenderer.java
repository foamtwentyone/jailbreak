package cellbreak.client;

import cellbreak.CbContent;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.WolfEntityRenderer;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.Identifier;

/** Guard dogs are German shepherds (the wolf model with a shepherd coat). */
public class GuardDogRenderer extends WolfEntityRenderer {
	private static final Identifier TEXTURE = CbContent.id("textures/entity/shepherd.png");

	public GuardDogRenderer(EntityRendererFactory.Context context) {
		super(context);
	}

	@Override
	public Identifier getTexture(WolfEntity wolf) {
		return TEXTURE;
	}
}
