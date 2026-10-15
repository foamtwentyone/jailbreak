package cellbreak.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import cellbreak.CbContent;
import cellbreak.game.CellbreakGame;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.minecraft.world.World;

/**
 * An NPC inmate: lives in a cell, follows the daily routine and fills the prison. Some of them run
 * the black market and sell makeshift tools for money.
 */
public class PrisonerNpcEntity extends MerchantEntity {
	private static final TrackedData<Boolean> TRADER = DataTracker.registerData(PrisonerNpcEntity.class,
			TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> VARIANT = DataTracker.registerData(PrisonerNpcEntity.class,
			TrackedDataHandlerRegistry.INTEGER);
	/** Number of different looks (skins). */
	public static final int VARIANTS = 16;

	/** How an inmate treats a player walking past. */
	public enum Personality {
		NEUTRAL, FRIENDLY, RUDE, JOKER
	}

	private static final String[] FRIENDLY = { "Хорошо выглядишь сегодня, %s!", "Держись, %s, прорвёмся.",
			"О, %s! Рад тебя видеть.", "Говорят, ты тут самый толковый. Уважаю.", "Классная причёска, %s.",
			"С тобой и срок быстрее идёт.", "Если что надо — обращайся, %s.", "Ты сегодня прям сияешь, %s." };
	private static final String[] RUDE = { "Чего вылупился, %s?", "Смотри, куда прёшь.",
			"%s, ты как местная баланда: никому не нравишься.", "Твоя мамка звонила: просила подержать тебя тут подольше.",
			"Ещё раз толкнёшь — пожалеешь.", "Иди-иди, черепаха.", "От тебя даже охрана нос воротит, %s.",
			"Твоя мама так медленно ходит, что её на перекличке до сих пор считают." };
	private static final String[] JOKES = { "Знаешь, почему охранник с дубинкой? Шутить не умеет.",
			"%s, у тебя шнурок развязался. Шучу, у нас шнурков нет.",
			"Твоя мама такая заботливая, что прислала тебе напильник в пироге. Шучу... или нет?",
			"%s, тебя на прогулку выпускают или выгуливают?", "Твоя мама звонила: спрашивала, почему ты до сих пор не сбежал. Стыдно ей.",
			"Ты в зеркало смотрелся, %s? Вот и охрана испугалась.", "Сидел тут один умник с ложкой. Говорят, до сих пор копает." };

	public static final String[] NICKNAMES = { "Косой", "Шрам", "Профессор", "Бублик", "Гвоздь", "Тихий", "Лысый",
			"Химик", "Боцман", "Сиплый", "Кабан", "Штопор", "Матрос", "Сухарь", "Жмых", "Кот", "Лом", "Пончик",
			"Дед", "Карась", "Шнырь", "Гоша", "Мазай", "Рыжий" };
	private static final String[] CHATTER = { "Чего уставился?", "Не мешай, у меня тут дела.",
			"Говорят, в вентиляции кто-то ползает...", "Ещё два дня, и я отсюда свалю.",
			"Обед сегодня опять мерзкий.", "За стенами море. Плавать умеешь?",
			"Охрана на перекличке шмонает, имей в виду.", "Металлодетектор в мастерской злой, осторожнее." };

	/** What the black-market traders can have on offer: price in money, item, count. */
	private static final Object[][] BLACK_MARKET = {
			{ 3, CbContent.FILE, 1 }, { 4, CbContent.MAKESHIFT_PICKAXE, 1 }, { 3, CbContent.MAKESHIFT_SHOVEL, 1 },
			{ 2, CbContent.SHIV, 1 }, { 6, CbContent.CELL_KEY, 1 }, { 2, CbContent.FAKE_WALL, 2 },
			{ 2, CbContent.FAKE_VENT, 1 }, { 1, Items.STRING, 3 }, { 1, Items.FLINT, 2 }, { 1, Items.PAPER, 3 },
			{ 1, Items.CLAY_BALL, 2 }, { 1, Items.IRON_NUGGET, 3 }, { 1, Items.COOKED_BEEF, 2 } };

	public int cell = -1;
	/** While above 0 the inmate sits in solitary (found with contraband at a search). */
	public long solitaryUntil;
	/** While above 0 the inmate lies in the infirmary (lost a fight). */
	public long infirmaryUntil;
	/** Where to stand in the line-up at roll call (set by the game), or null. */
	public BlockPos lineupSlot;
	public BlockPos lineupLook;
	/** Decided at each meal: some inmates skip it. */
	public boolean skipsMeal;
	public Personality personality = Personality.NEUTRAL;
	private int talkCooldown;
	private int angryTicks;

	public PrisonerNpcEntity(EntityType<? extends MerchantEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
	}

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason, EntityData data) {
		EntityData result = super.initialize(world, difficulty, reason, data);
		dataTracker.set(VARIANT, random.nextInt(VARIANTS));
		Personality[] all = Personality.values();
		personality = all[random.nextInt(all.length)];
		talkCooldown = random.nextInt(200);
		return result;
	}

	public int getVariant() {
		return dataTracker.get(VARIANT);
	}

	public static DefaultAttributeContainer.Builder createPrisonerAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(TRADER, false);
		builder.add(VARIANT, 0);
	}

	public boolean isTrader() {
		return dataTracker.get(TRADER);
	}

	public void setTrader(boolean trader) {
		dataTracker.set(TRADER, trader);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(1, new MeleeAttackGoal(this, 1.2, true));
		goalSelector.add(2, new RoutineGoal(this));
		goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		goalSelector.add(4, new LookAroundGoal(this));
		targetSelector.add(1, new RevengeGoal(this));
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		LivingEntity target = getTarget();
		if (target != null) {
			// A fight does not last forever; and one who left (solitary, infirmary) is forgotten.
			if (++angryTicks > 20 * 20 || !target.isAlive() || squaredDistanceTo(target) > 24 * 24
					|| !(target instanceof PlayerEntity tp) || infirmaryUntil != 0
					|| game() != null && game().isOutOfPlay(tp)) {
				setTarget(null);
				setAttacker(null);
				angryTicks = 0;
			}
		} else {
			angryTicks = 0;
		}
		if (--talkCooldown <= 0) {
			talkCooldown = 20;
			talkToPasserby();
		}
	}

	/** Says something to a player prisoner walking right past: a compliment, a jibe or a joke. */
	private void talkToPasserby() {
		CellbreakGame game = game();
		if (game == null || !game.isRunning() || personality == Personality.NEUTRAL || getTarget() != null || hasCustomer()) {
			return;
		}
		PlayerEntity near = getWorld().getClosestPlayer(this, 3.0);
		if (!(near instanceof ServerPlayerEntity p) || !game.isPrisoner(p) || !canSee(p) || !game.mayTalkTo(p)) {
			return;
		}
		String[] lines = switch (personality) {
			case FRIENDLY -> FRIENDLY;
			case RUDE -> RUDE;
			default -> JOKES;
		};
		String line = String.format(lines[random.nextInt(lines.length)], p.getName().getString());
		Formatting color = personality == Personality.FRIENDLY ? Formatting.GREEN
				: personality == Personality.RUDE ? Formatting.RED : Formatting.YELLOW;
		Text msg = Text.literal(getName().getString() + ": ").formatted(Formatting.GOLD)
				.append(Text.literal(line).formatted(color));
		for (PlayerEntity other : getWorld().getPlayers()) {
			if (other.squaredDistanceTo(this) < 12 * 12) {
				other.sendMessage(msg, false);
			}
		}
		getLookControl().lookAt(p);
		talkCooldown = 20 * (40 + random.nextInt(40));
	}

	@Override
	public boolean tryAttack(net.minecraft.entity.Entity target) {
		swingHand(Hand.MAIN_HAND);
		return super.tryAttack(target);
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND || !isAlive()) {
			return ActionResult.PASS;
		}
		if (getWorld().isClient) {
			return ActionResult.SUCCESS;
		}
		if (!isTrader()) {
			player.sendMessage(Text.literal(getName().getString() + ": ").formatted(Formatting.GOLD)
					.append(Text.literal(CHATTER[random.nextInt(CHATTER.length)]).formatted(Formatting.WHITE)), false);
			return ActionResult.SUCCESS;
		}
		if (!hasCustomer() && !getOffers().isEmpty()) {
			setCustomer(player);
			sendOffers(player, getDisplayName(), 1);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	protected void fillRecipes() {
		TradeOfferList offers = getOffers();
		List<Object[]> pool = new ArrayList<>(List.of(BLACK_MARKET));
		Collections.shuffle(pool, new java.util.Random(getUuid().getLeastSignificantBits()));
		for (Object[] o : pool.subList(0, 5)) {
			offers.add(new TradeOffer(new TradedItem(CbContent.MONEY, (Integer) o[0]),
					new ItemStack((Item) o[1], (Integer) o[2]), 3, 0, 0.0F));
		}
		// Traders also buy scrap metal.
		offers.add(new TradeOffer(new TradedItem(Items.IRON_INGOT, 1), new ItemStack(CbContent.MONEY, 1), 8, 0, 0.0F));
	}

	/** New day, new stock. */
	public void restock() {
		for (TradeOffer offer : getOffers()) {
			offer.resetUses();
		}
	}

	@Override
	protected void afterUsing(TradeOffer offer) {
	}

	@Override
	public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
		return null;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("Cell", cell);
		nbt.putBoolean("Trader", isTrader());
		nbt.putInt("Variant", getVariant());
		nbt.putString("Personality", personality.name());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		cell = nbt.getInt("Cell");
		setTrader(nbt.getBoolean("Trader"));
		dataTracker.set(VARIANT, nbt.getInt("Variant"));
		try {
			personality = Personality.valueOf(nbt.getString("Personality"));
		} catch (IllegalArgumentException e) {
			personality = Personality.NEUTRAL;
		}
	}

	private CellbreakGame game() {
		return getWorld() instanceof ServerWorld sw ? CellbreakGame.get(sw.getServer()) : null;
	}

	/** Walks where the routine says; if the way is blocked for long (ladders), takes a shortcut. */
	static class RoutineGoal extends Goal {
		private final PrisonerNpcEntity npc;
		private BlockPos destination;
		private int travelTicks;
		private int waitTicks;

		RoutineGoal(PrisonerNpcEntity npc) {
			this.npc = npc;
			setControls(EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			return npc.game() != null && npc.game().isRunning() && npc.getTarget() == null;
		}

		@Override
		public void tick() {
			CellbreakGame game = npc.game();
			if (game == null || !npc.isAlive() || npc.hasCustomer()) {
				npc.getNavigation().stop();
				return;
			}
			boolean working = game.isWorkingAt(npc.getBlockPos());
			ItemStack held = npc.getMainHandStack();
			if (working != held.isOf(CbContent.WORK_PICKAXE)) {
				npc.equipStack(EquipmentSlot.MAINHAND, working ? new ItemStack(CbContent.WORK_PICKAXE) : ItemStack.EMPTY);
			}
			if (waitTicks > 0) {
				waitTicks--;
				if (npc.lineupSlot != null && npc.lineupLook != null && npc.getBlockPos().getSquaredDistance(npc.lineupSlot) <= 2) {
					// Step onto the exact spot in the row and face the front.
					double x = npc.lineupSlot.getX() + 0.5;
					double z = npc.lineupSlot.getZ() + 0.5;
					if (npc.squaredDistanceTo(x, npc.getY(), z) > 0.04) {
						npc.getMoveControl().moveTo(x, npc.lineupSlot.getY(), z, 0.6);
					}
					npc.getLookControl().lookAt(npc.lineupLook.getX() + 0.5, npc.getEyeY(), npc.lineupLook.getZ() + 0.5);
					float yaw = (float) (Math.toDegrees(Math.atan2(npc.lineupLook.getZ() + 0.5 - z, npc.lineupLook.getX() + 0.5 - x)) - 90);
					npc.setBodyYaw(yaw);
				} else if (working && npc.getRandom().nextInt(15) == 0) {
					npc.swingHand(Hand.MAIN_HAND);
				}
				return;
			}
			BlockPos wanted = game.npcDestination(npc, destination);
			if (wanted == null) {
				return;
			}
			if (!wanted.equals(destination)) {
				destination = wanted;
				travelTicks = 0;
				Path path = npc.getNavigation().findPathTo(destination, 0);
				npc.getNavigation().startMovingAlong(path, 1.0);
			}
			travelTicks++;
			if (npc.getBlockPos().getSquaredDistance(destination) <= 2) {
				npc.getNavigation().stop();
				// In the line-up they stand still and wait; elsewhere they idle a bit before moving on.
				waitTicks = npc.lineupSlot != null ? 40 : 40 + npc.getRandom().nextInt(100);
				if (npc.lineupSlot == null) {
					destination = null;
				}
			} else if (travelTicks > 15 * 20) {
				// Out of walking reach: the inmate "took another corridor".
				npc.refreshPositionAndAngles(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5,
						npc.getYaw(), npc.getPitch());
				npc.getNavigation().stop();
				travelTicks = 0;
			} else if (travelTicks % 30 == 0 && npc.getNavigation().isIdle()) {
				npc.getNavigation().startMovingTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 1.0);
			}
		}
	}
}
