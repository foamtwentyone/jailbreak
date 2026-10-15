package cellbreak.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import cellbreak.CbContent;
import cellbreak.game.CellbreakGame;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
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
import net.minecraft.server.world.ServerWorld;
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

	public PrisonerNpcEntity(EntityType<? extends MerchantEntity> type, World world) {
		super(type, world);
		setPersistent();
		setInvulnerable(true);
		setCanPickUpLoot(false);
	}

	public static DefaultAttributeContainer.Builder createPrisonerAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.27)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(TRADER, false);
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
		goalSelector.add(1, new RoutineGoal(this));
		goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		goalSelector.add(3, new LookAroundGoal(this));
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
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		cell = nbt.getInt("Cell");
		setTrader(nbt.getBoolean("Trader"));
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
			return npc.game() != null && npc.game().isRunning();
		}

		@Override
		public void tick() {
			CellbreakGame game = npc.game();
			if (game == null || !npc.isAlive() || npc.hasCustomer()) {
				npc.getNavigation().stop();
				return;
			}
			if (waitTicks > 0) {
				waitTicks--;
				return;
			}
			BlockPos wanted = game.npcDestination(npc, destination);
			if (wanted == null) {
				return;
			}
			if (!wanted.equals(destination)) {
				destination = wanted;
				travelTicks = 0;
				Path path = npc.getNavigation().findPathTo(destination, 1);
				npc.getNavigation().startMovingAlong(path, 0.7);
			}
			travelTicks++;
			if (npc.getBlockPos().getSquaredDistance(destination) <= 4) {
				npc.getNavigation().stop();
				waitTicks = 60 + npc.getRandom().nextInt(140);
				destination = null;
			} else if (travelTicks > 30 * 20) {
				// Out of walking reach: the inmate "took another corridor".
				npc.refreshPositionAndAngles(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5,
						npc.getYaw(), npc.getPitch());
				npc.getNavigation().stop();
				destination = null;
			} else if (travelTicks % 40 == 0 && npc.getNavigation().isIdle()) {
				npc.getNavigation().startMovingTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 0.7);
			}
		}
	}
}
