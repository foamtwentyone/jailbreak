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

	public static final String[] NICKNAMES = { "Косой", "Шрам", "Профессор", "Бублик", "Гвоздь", "Тихий", "Лысый",
			"Химик", "Боцман", "Сиплый", "Кабан", "Штопор", "Матрос", "Сухарь", "Жмых", "Кот", "Лом", "Пончик",
			"Дед", "Карась", "Шнырь", "Гоша", "Мазай", "Рыжий", "Клещ", "Сова", "Гусь", "Валет", "Шайба", "Фомич" };

	/** How an inmate treats a player walking past. */
	public enum Personality {
		NEUTRAL, FRIENDLY, RUDE, JOKER, VETERAN, PARANOID, PHILOSOPHER, BRAGGART
	}

	// %s is the player's name.
	private static final String[] FRIENDLY = { "Хорошо выглядишь сегодня, %s!", "Держись, %s, прорвёмся.",
			"О, %s! Рад тебя видеть.", "Говорят, ты тут самый толковый. Уважаю.", "Классная причёска, %s.",
			"С тобой и срок быстрее идёт.", "Если что надо — обращайся, %s.", "Ты сегодня прям сияешь, %s.",
			"%s, у тебя честное лицо. Тут это редкость.", "Слышал, ты вчера здорово отработал. Молоток!",
			"Не грусти, %s. Солнце светит и для нас.", "Ты один из немногих, с кем тут можно поговорить.",
			"%s, хочешь, поделюсь хлебом?", "Ты держишься лучше всех, кого я видел за пять лет.",
			"Если кто обидит — скажи мне, %s.", "Отличная форма, %s! Прямо как из каталога.",
			"Хорошего дня, %s. Ну, насколько он тут может быть хорошим.", "%s, ты как глоток свежего воздуха в этой дыре.",
			"У тебя добрые глаза, %s. Береги их.", "Уважаю тех, кто не ноет. Ты из таких." };
	private static final String[] RUDE = { "Чего вылупился, %s?", "Смотри, куда прёшь.",
			"%s, ты как местная баланда: никому не нравишься.", "Твоя мамка звонила: просила подержать тебя тут подольше.",
			"Ещё раз толкнёшь — пожалеешь.", "Иди-иди, черепаха.", "От тебя даже охрана нос воротит, %s.",
			"Твоя мама так медленно ходит, что её на перекличке до сих пор считают.", "Отвали, %s, не до тебя.",
			"%s, ты на свиданиях тоже так пялишься? Понятно, почему ты здесь.", "Глаза опусти, новенький.",
			"Твоя мама приносила передачку. Мы съели. Вкусно было.", "Ты здесь самый бесполезный, %s. Даже стулья полезнее.",
			"Ещё слово — и поедешь в медпункт.", "%s, от тебя пахнет как от душевой после прогулки.",
			"Твоя мама говорит, что ты в детстве ел мел. Это многое объясняет.", "Не стой на моём месте, %s.",
			"Тебя даже снайперы не замечают, %s. Настолько ты пустое место.", "Иди поплачь в подушку, %s.",
			"Твоя мама так плохо готовит, что баланда тебе тут как ресторан." };
	private static final String[] JOKES = { "Знаешь, почему охранник с дубинкой? Шутить не умеет.",
			"%s, у тебя шнурок развязался. Шучу, у нас шнурков нет.",
			"Твоя мама такая заботливая, что прислала тебе напильник в пироге. Шучу... или нет?",
			"%s, тебя на прогулку выпускают или выгуливают?", "Твоя мама звонила: спрашивала, почему ты до сих пор не сбежал. Стыдно ей.",
			"Ты в зеркало смотрелся, %s? Вот и охрана испугалась.", "Сидел тут один умник с ложкой. Говорят, до сих пор копает.",
			"Что общего у тебя и решётки, %s? Вы оба тут надолго.", "Как называется заключённый с лопатой? Оптимист.",
			"Твоя мама так любит тебя, что даже срок тебе выбила побольше.", "Охранник спрашивает: «Почему ты не спишь?» А я: «Сторожу вас».",
			"%s, говорят, у тебя золотые руки. Вот их и посадили вместе с тобой.",
			"Почему снайперы не играют в прятки? Потому что всегда находят. Ха!",
			"Знаешь, как тут называют оптимиста? Новенький. Как тебя, %s.", "У нас тут как в санатории. Только выйти нельзя.",
			"Твоя мама прислала письмо: «Сынок, учись хорошо». Я ей ответил: «Учится — копать».",
			"%s, если увидишь мою свободу — передай привет.", "Повар сказал, что это котлета. Мы до сих пор спорим, чья.",
			"Что сказал кирпич, когда я его ударил? Ничего. Он кирпич.", "Твоя мама — единственная, кто верит, что ты тут случайно." };
	private static final String[] VETERAN = { "Слушай старика, %s: не спорь с охраной на перекличке.",
			"Я тут двадцать лет. Стены везде одинаковые, а вот решётки — нет.", "Напильник держи в тумбочке, а не в кармане.",
			"Металлодетектор в мастерской ловит даже самородки. Проверено мной.", "Ночью держись в тени, %s. Прожекторы не спят.",
			"Охрана заделывает дыры, если увидит. А если не увидит — нет.", "Торговцы дерут втридорога. Работай на поле, копи.",
			"Вентиляция — дорога для худых и терпеливых.", "Снайпер днём стреляет, только если копаешь у него на глазах.",
			"Когда-то тут были женские душевые. Теперь медпункт. Времена меняются.",
			"Если охранник смотрит — делай вид, что ты мебель.", "Не рисуй на стенах, %s. Это всегда находят.",
			"Самодельная кирка ломается в самый неподходящий момент. Носи запасную.", "Не дерись при охране. Это быстрый путь в карцер.",
			"Перекличку пропускать — себе дороже.", "Я видел, как отсюда сбегали. Видел и как возвращали.",
			"Муляж стены спасёт тебе жизнь. Ну, или срок.", "Ключ из глины — лучший друг заключённого.",
			"Ночью двери запирают через полминуты после звонка. Не опаздывай.", "Терпение, %s. Стены тоже стареют." };
	private static final String[] PARANOID = { "%s, ты тоже слышишь, как стены шепчут?", "Охрана читает наши мысли. Думай тише.",
			"Не смотри на снайпера! Он это чувствует.", "Они кладут что-то в баланду. Я точно знаю.",
			"Этот сканер видит сквозь кожу, %s. Я тебе говорю.", "Тот новенький — подсадной. Не верь ему.",
			"Каждую ночь кто-то двигает мою кровать на сантиметр.", "%s, ты за мной не следишь? Точно?",
			"Голуби на стене — с камерами. Все до одного.", "Если охранник улыбается — значит, что-то знает.",
			"Я не сплю уже три дня. Так безопаснее.", "Говорят, под тюрьмой есть ещё одна тюрьма.",
			"Не пей воду из душа, %s. Просто не пей.", "Они пересчитывают нас, потому что кого-то подменили.",
			"Мои тапки пропали. Это заговор.", "Тсс! Деревья на поле подслушивают.", "Почему у охраны все шапки одинаковые? Подозрительно.",
			"%s, если я пропаду — ищи в вентиляции.", "Начальник тюрьмы — робот. Я видел, как он не моргал.",
			"Мне кажется, моя тень работает на охрану." };
	private static final String[] PHILOSOPHER = { "Свобода, %s, — это когда решётка только снаружи.",
			"Мы все в тюрьме. Просто у некоторых стены красивее.", "Время здесь течёт иначе. Как баланда — медленно и вязко.",
			"Что такое побег, %s? Возвращение к себе.", "Стена — это всего лишь вопрос, на который ещё не ответили киркой.",
			"Каждый кирпич — урок терпения.", "Охранники тоже заключённые. Только по ту сторону.",
			"Ночь длинная, а мысли ещё длиннее.", "Человек — это то, что он делает, когда никто не смотрит.",
			"Если снайпер не видит тебя — существуешь ли ты?", "Счастье — это когда тебя не обыскали на перекличке.",
			"Мы сами строим свои стены, %s. Эти, правда, построил кто-то другой.", "Тишина в камере громче любого крика.",
			"Даже трещина в стене — это начало пути.", "Ложка в руках мудреца — инструмент. В руках глупца — ложка.",
			"Дорога в тысячу шагов начинается с одного удара кирки.", "Всё проходит. Срок тоже.",
			"Мел на стене смывают, а мысль — нет.", "Не бойся темноты, %s. Бойся прожектора.", "Кто знает себя, тот знает выход." };
	private static final String[] BRAGGART = { "Я отсюда уже трижды сбегал. Просто возвращаюсь — скучно на воле.",
			"Видел мою кирку? Сам сделал. Из ложки и характера.", "Охранники меня боятся, %s. Ну, уважают.",
			"Я тут самый сильный. Спроси любого. Только не охрану.", "Мне торговцы сами приплачивают, чтобы я у них покупал.",
			"Я как-то голыми руками решётку согнул. Ну, почти.", "На поле я добываю больше всех. Руда меня любит.",
			"Знаешь, кто придумал вентиляцию? Не я, но я её улучшил.", "Меня даже снайпер ни разу не заметил. Я тень.",
			"%s, хочешь автограф? Потом дорого продашь.", "Я в карцере сидел столько, что там табличку с моим именем повесили.",
			"Мой рекорд: восемь блоков за одну ночь. Никто не побьёт.", "Начальник тюрьмы со мной здоровается за руку.",
			"Я бы давно ушёл, но кто тогда будет вас учить?", "Мой муляж стены даже охрана хвалила. Не зная, что это муляж.",
			"Я дерусь так, что медпункт для меня отдельную койку держит.", "Меня сюда посадили, потому что на воле я всех обыгрывал.",
			"Видишь этот шрам? Это я спас охранника. Он, правда, не помнит.", "Я читал все книги в библиотеке. Обе.",
			"%s, держись рядом — научу жизни." };
	private static final String[] NEUTRAL_LINES = { "Угу.", "Привет.", "...", "Ну и денёк.", "Скорей бы ужин.",
			"Опять дождь, что ли?", "Ты не видел мои носки?", "Мм." };

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
	/** The last hit already answered: a new hit makes the inmate angry again, an old one does not. */
	private int answeredHit = -1;

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
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 160.0);
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
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		// Hit by a player: hits back for a while, then lets it go (until hit again).
		LivingEntity attacker = getAttacker();
		if (attacker instanceof PlayerEntity && getLastAttackedTime() != answeredHit && infirmaryUntil == 0) {
			answeredHit = getLastAttackedTime();
			setTarget(attacker);
			angryTicks = 0;
		}
		LivingEntity target = getTarget();
		if (target != null) {
			if (++angryTicks > 10 * 20 || !target.isAlive() || squaredDistanceTo(target) > 16 * 16
					|| !(target instanceof PlayerEntity tp) || infirmaryUntil != 0
					|| game() != null && game().isOutOfPlay(tp)) {
				calmDown();
			}
		}
		if (--talkCooldown <= 0) {
			talkCooldown = 20;
			talkToPasserby();
		}
	}

	/** Stops fighting (time is up, or a guard broke the fight up). */
	public void calmDown() {
		setTarget(null);
		angryTicks = 0;
		getNavigation().stop();
	}

	/** Says something to a player prisoner walking right past: a compliment, a jibe or a joke. */
	private void talkToPasserby() {
		CellbreakGame game = game();
		if (game == null || !game.isRunning() || getTarget() != null || hasCustomer()
				|| personality == Personality.NEUTRAL && random.nextInt(4) != 0) {
			return;
		}
		PlayerEntity near = getWorld().getClosestPlayer(this, 3.0);
		if (!(near instanceof ServerPlayerEntity p) || !game.isPrisoner(p) || !canSee(p) || !game.mayTalkTo(p)) {
			return;
		}
		say(p, true);
		getLookControl().lookAt(p);
		talkCooldown = 20 * (40 + random.nextInt(40));
	}

	/** One line in this inmate's manner to the player (and everybody near, if out loud). */
	private void say(PlayerEntity p, boolean outLoud) {
		String[] lines = switch (personality) {
			case FRIENDLY -> FRIENDLY;
			case RUDE -> RUDE;
			case JOKER -> JOKES;
			case VETERAN -> VETERAN;
			case PARANOID -> PARANOID;
			case PHILOSOPHER -> PHILOSOPHER;
			case BRAGGART -> BRAGGART;
			default -> NEUTRAL_LINES;
		};
		String line = String.format(lines[random.nextInt(lines.length)], p.getName().getString());
		Formatting color = switch (personality) {
			case FRIENDLY -> Formatting.GREEN;
			case RUDE -> Formatting.RED;
			case JOKER -> Formatting.YELLOW;
			case VETERAN -> Formatting.AQUA;
			case PARANOID -> Formatting.LIGHT_PURPLE;
			case PHILOSOPHER -> Formatting.DARK_AQUA;
			case BRAGGART -> Formatting.GOLD;
			default -> Formatting.GRAY;
		};
		Text msg = Text.literal(getName().getString() + ": ").formatted(Formatting.GOLD)
				.append(Text.literal(line).formatted(color));
		for (PlayerEntity other : getWorld().getPlayers()) {
			if (other.squaredDistanceTo(this) < 12 * 12) {
				if (outLoud || other == p) {
					other.sendMessage(msg, false);
				}
			}
		}
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
			say(player, false);
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
		/** The destination cannot be reached on foot: the inmate does not try, it takes a side door. */
		private boolean unreachable;
		/** On the way through this waypoint. */
		private BlockPos via;

		/** A path that gets (nearly) to the point, or null. */
		private Path goodPath(BlockPos to) {
			Path path = npc.getNavigation().findPathTo(to, 0);
			if (path == null || path.getEnd() == null) {
				return null;
			}
			return path.reachesTarget() || path.getEnd().getBlockPos().getSquaredDistance(to) <= 4 ? path : null;
		}

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
			if (!wanted.equals(destination) || via != null && npc.getBlockPos().getSquaredDistance(via) <= 4) {
				destination = wanted;
				travelTicks = 0;
				via = null;
				Path path = goodPath(destination);
				if (path == null) {
					// Too far for one path: walk to the waypoint closest to the goal that can be reached.
					for (BlockPos w : game.waypointsToward(npc.getBlockPos(), destination)) {
						path = goodPath(w);
						if (path != null) {
							via = w;
							break;
						}
					}
				}
				// No way at all (a path would stop in a cell corner or under the canteen): side door.
				unreachable = path == null;
				if (unreachable) {
					npc.getNavigation().stop();
				} else {
					npc.getNavigation().startMovingAlong(path, 1.0);
				}
			}
			travelTicks++;
			BlockPos at = npc.getBlockPos();
			boolean arrived = Math.abs(at.getY() - destination.getY()) <= 1
					&& at.getSquaredDistance(destination.getX(), at.getY(), destination.getZ()) <= 2;
			if (unreachable && travelTicks > 40) {
				npc.refreshPositionAndAngles(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5,
						npc.getYaw(), npc.getPitch());
				npc.getNavigation().stop();
				unreachable = false;
				arrived = true;
			}
			if (arrived) {
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
			} else if (!unreachable && travelTicks % 30 == 0 && npc.getNavigation().isIdle()) {
				npc.getNavigation().startMovingTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 1.0);
			}
		}
	}
}
