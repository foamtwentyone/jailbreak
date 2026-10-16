package cellbreak.entity;

import java.util.EnumSet;

import cellbreak.game.CellbreakGame;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A canteen cook: stands at the serving hatch and hands out food at meals. One is gloomy, the
 * other cheerful (VARIANT 0 and 1).
 */
public class CookEntity extends PathAwareEntity {
	private static final TrackedData<Integer> VARIANT = DataTracker.registerData(CookEntity.class,
			TrackedDataHandlerRegistry.INTEGER);

	private static final String[] GLOOMY = { "Бери и проходи.", "Не задерживай очередь.", "Добавки нет. И не будет.",
			"Что дали, то и ешь.", "Ещё раз пожалуешься — получишь вчерашнее.", "Ложку верни потом.",
			"Я тридцать лет у плиты. Мне не до улыбок.", "Опять ты. Ну держи.", "Хлеб чёрствый? Зубы крепче будут.",
			"Не нравится — на воле поешь.", "Следующий!", "Баланда как баланда. Чего уставился?",
			"Тарелку не урони. Новую не дам.", "Мне за улыбки не платят.", "Тише там, в очереди!",
			"Повар не официант. Бери сам.", "Соль кончилась. Как и моё терпение.", "Ешь молча." };
	private static final String[] CHEERFUL = { "Приятного аппетита, дорогой!", "Сегодня баланда особенная — с любовью!",
			"Кушай-кушай, тебе силы нужны!", "О, мой любимый клиент! Держи порцию побольше.",
			"Секретный ингредиент — улыбка!", "Не переживай, всё будет хорошо. А пока — хлебушек!",
			"Я сегодня пою, пока готовлю. Слышал?", "Добавки не обещаю, но подмигнуть могу!",
			"Ешь с удовольствием — так полезнее!", "Ты сегодня бодрый! Это от моей каши.",
			"Держи, ещё горяченькое!", "Говорят, мой хлеб лучший в тюрьме. Я сам так говорю.",
			"Улыбнись! Еда вкуснее, когда улыбаешься.", "Возьми, пока Степаныч не видит!",
			"Ммм, понюхай! Чувствуешь? Это лавровый лист!", "Следующий, мой хороший!", "Ешь на здоровье!",
			"Кто хорошо ест, тот хорошо копает... ой, работает!" };

	private BlockPos post;

	public CookEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setInvulnerable(true);
		setCanPickUpLoot(false);
	}

	public static DefaultAttributeContainer.Builder createCookAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(VARIANT, 0);
	}

	public int getVariant() {
		return dataTracker.get(VARIANT);
	}

	public boolean isCheerful() {
		return getVariant() == 1;
	}

	/** Sets who this cook is (0 gloomy, 1 cheerful) and where they stand. */
	public void setup(int variant, BlockPos post) {
		dataTracker.set(VARIANT, variant);
		this.post = post;
		setCustomName(Text.literal(variant == 1 ? "Повар Гена" : "Повар Степаныч")
				.formatted(variant == 1 ? Formatting.YELLOW : Formatting.GRAY));
		setCustomNameVisible(true);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(1, new StayAtPostGoal(this));
		goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		goalSelector.add(3, new LookAroundGoal(this));
	}

	/** A line in this cook's manner. */
	public Text line() {
		String[] lines = isCheerful() ? CHEERFUL : GLOOMY;
		return Text.literal(getName().getString() + ": ").formatted(Formatting.GOLD)
				.append(Text.literal(lines[random.nextInt(lines.length)])
						.formatted(isCheerful() ? Formatting.YELLOW : Formatting.GRAY));
	}

	private static final String[] CHEST_GLOOMY = { "Руки убрал от продуктов. Сейчас охрану позову.",
			"Это общак кухни, а не твой. Положи на место.", "Ещё раз полезешь — без ужина неделю." };
	private static final String[] CHEST_CHEERFUL = { "Эй-эй, дорогой, это же на всех! Охрана, тут гость!",
			"Ай-яй-яй, в сундук к повару? Нехорошо!", "Ну куда, ну куда?! Это на ужин всему блоку!" };

	/** What the cook says to someone rummaging in the kitchen chest. */
	public Text chestLine() {
		String[] lines = isCheerful() ? CHEST_CHEERFUL : CHEST_GLOOMY;
		return Text.literal(getName().getString() + ": ").formatted(Formatting.GOLD)
				.append(Text.literal(lines[random.nextInt(lines.length)]).formatted(Formatting.RED));
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (getWorld() instanceof ServerWorld sw && player instanceof ServerPlayerEntity sp) {
			swingHand(Hand.MAIN_HAND);
			CellbreakGame.get(sw.getServer()).askCookForFood(this, sp);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("Variant", getVariant());
		if (post != null) {
			nbt.putLong("Post", post.asLong());
		}
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		dataTracker.set(VARIANT, nbt.getInt("Variant"));
		if (nbt.contains("Post")) {
			post = BlockPos.fromLong(nbt.getLong("Post"));
		}
	}

	/** Goes back behind the counter if pushed away. */
	static class StayAtPostGoal extends Goal {
		private final CookEntity cook;

		StayAtPostGoal(CookEntity cook) {
			this.cook = cook;
			setControls(EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			return cook.post != null && cook.getBlockPos().getSquaredDistance(cook.post) > 1;
		}

		@Override
		public void start() {
			cook.getNavigation().startMovingTo(cook.post.getX() + 0.5, cook.post.getY(), cook.post.getZ() + 0.5, 0.8);
		}

		@Override
		public boolean shouldContinue() {
			return !cook.getNavigation().isIdle();
		}
	}
}
