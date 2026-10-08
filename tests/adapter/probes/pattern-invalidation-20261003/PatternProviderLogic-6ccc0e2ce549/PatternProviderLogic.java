/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  appeng.api.config.Actionable
 *  appeng.api.config.LockCraftingMode
 *  appeng.api.config.Setting
 *  appeng.api.config.Settings
 *  appeng.api.config.YesNo
 *  appeng.api.crafting.IPatternDetails
 *  appeng.api.crafting.IPatternDetails$IInput
 *  appeng.api.crafting.PatternDetailsHelper
 *  appeng.api.implementations.blockentities.ICraftingMachine
 *  appeng.api.implementations.blockentities.PatternContainerGroup
 *  appeng.api.inventories.InternalInventory
 *  appeng.api.networking.GridFlags
 *  appeng.api.networking.IGrid
 *  appeng.api.networking.IGridConnection
 *  appeng.api.networking.IGridNode
 *  appeng.api.networking.IGridNodeService
 *  appeng.api.networking.IManagedGridNode
 *  appeng.api.networking.crafting.ICraftingProvider
 *  appeng.api.networking.security.IActionSource
 *  appeng.api.networking.ticking.IGridTickable
 *  appeng.api.stacks.AEKey
 *  appeng.api.stacks.GenericStack
 *  appeng.api.stacks.KeyCounter
 *  appeng.api.util.IConfigManager
 *  appeng.core.AELog
 *  appeng.core.definitions.AEItems
 *  appeng.core.localization.GuiText
 *  appeng.core.localization.PlayerMessages
 *  appeng.helpers.InterfaceLogicHost
 *  appeng.helpers.patternprovider.PatternProviderLogic$1
 *  appeng.helpers.patternprovider.PatternProviderLogic$1PushTarget
 *  appeng.helpers.patternprovider.PatternProviderLogic$Ticker
 *  appeng.helpers.patternprovider.PatternProviderLogicHost
 *  appeng.helpers.patternprovider.PatternProviderReturnInventory
 *  appeng.helpers.patternprovider.PatternProviderTarget
 *  appeng.helpers.patternprovider.PatternProviderTargetCache
 *  appeng.helpers.patternprovider.UnlockCraftingEvent
 *  appeng.me.helpers.MachineSource
 *  appeng.util.ConfigManager
 *  appeng.util.inv.AppEngInternalInventory
 *  appeng.util.inv.InternalInventoryHost
 *  appeng.util.inv.PlayerInternalInventory
 *  it.unimi.dsi.fastutil.objects.Object2LongMap$Entry
 *  net.minecraft.ChatFormatting
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.Nameable
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraftforge.common.capabilities.Capability
 *  net.minecraftforge.common.util.LazyOptional
 *  org.jetbrains.annotations.Nullable
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package appeng.helpers.patternprovider;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.IConfigManager;
import appeng.core.AELog;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import appeng.core.localization.PlayerMessages;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.helpers.patternprovider.PatternProviderTargetCache;
import appeng.helpers.patternprovider.UnlockCraftingEvent;
import appeng.me.helpers.MachineSource;
import appeng.util.ConfigManager;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.PlayerInternalInventory;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PatternProviderLogic
implements InternalInventoryHost,
ICraftingProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternProviderLogic.class);
    public static final String NBT_MEMORY_CARD_PATTERNS = "patterns";
    public static final String NBT_UNLOCK_EVENT = "unlockEvent";
    public static final String NBT_UNLOCK_STACK = "unlockStack";
    public static final String NBT_PRIORITY = "priority";
    public static final String NBT_SEND_LIST = "sendList";
    public static final String NBT_SEND_DIRECTION = "sendDirection";
    public static final String NBT_RETURN_INV = "returnInv";
    private final PatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;
    private final ConfigManager configManager = new ConfigManager(this::configChanged);
    private int priority;
    private final AppEngInternalInventory patternInventory;
    private final List<IPatternDetails> patterns = new ArrayList<IPatternDetails>();
    private final Set<AEKey> patternInputs = new HashSet<AEKey>();
    private final List<GenericStack> sendList = new ArrayList<GenericStack>();
    private Direction sendDirection;
    private final PatternProviderReturnInventory returnInv;
    private final PatternProviderTargetCache[] targetCaches = new PatternProviderTargetCache[6];
    private YesNo redstoneState = YesNo.UNDECIDED;
    @Nullable
    private UnlockCraftingEvent unlockEvent;
    @Nullable
    private GenericStack unlockStack;
    private int roundRobinIndex = 0;

    public @Nullable PatternProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host) {
        this(mainNode, host, 9);
    }

    public PatternProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host, int patternInventorySize) {
        this.patternInventory = new AppEngInternalInventory((InternalInventoryHost)this, patternInventorySize);
        this.host = host;
        this.mainNode = mainNode.setFlags(new GridFlags[]{GridFlags.REQUIRE_CHANNEL}).addService(IGridTickable.class, (IGridNodeService)new Ticker(this)).addService(ICraftingProvider.class, (IGridNodeService)this);
        this.actionSource = new MachineSource(() -> ((IManagedGridNode)mainNode).getNode());
        this.configManager.registerSetting(Settings.BLOCKING_MODE, (Enum)YesNo.NO);
        this.configManager.registerSetting(Settings.PATTERN_ACCESS_TERMINAL, (Enum)YesNo.YES);
        this.configManager.registerSetting(Settings.LOCK_CRAFTING_MODE, (Enum)LockCraftingMode.NONE);
        this.returnInv = new PatternProviderReturnInventory(() -> {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            this.host.saveChanges();
        });
    }

    public int getPriority() {
        return this.priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.host.saveChanges();
        ICraftingProvider.requestUpdate((IManagedGridNode)this.mainNode);
    }

    public void writeToNBT(CompoundTag tag) {
        this.configManager.writeToNBT(tag);
        this.patternInventory.writeToNBT(tag, NBT_MEMORY_CARD_PATTERNS);
        tag.m_128405_(NBT_PRIORITY, this.priority);
        if (this.unlockEvent == UnlockCraftingEvent.REDSTONE_POWER) {
            tag.m_128344_(NBT_UNLOCK_EVENT, (byte)1);
        } else if (this.unlockEvent == UnlockCraftingEvent.RESULT) {
            if (this.unlockStack != null) {
                tag.m_128344_(NBT_UNLOCK_EVENT, (byte)2);
                tag.m_128365_(NBT_UNLOCK_STACK, (Tag)GenericStack.writeTag((GenericStack)this.unlockStack));
            } else {
                LOGGER.error("Saving pattern provider {}, locked waiting for stack, but stack is null!", (Object)this.host);
            }
        } else if (this.unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE) {
            tag.m_128344_(NBT_UNLOCK_EVENT, (byte)3);
        }
        ListTag sendListTag = new ListTag();
        for (GenericStack toSend : this.sendList) {
            sendListTag.add((Object)GenericStack.writeTag((GenericStack)toSend));
        }
        tag.m_128365_(NBT_SEND_LIST, (Tag)sendListTag);
        if (this.sendDirection != null) {
            tag.m_128344_(NBT_SEND_DIRECTION, (byte)this.sendDirection.m_122411_());
        }
        tag.m_128365_(NBT_RETURN_INV, (Tag)this.returnInv.writeToTag());
    }

    public void readFromNBT(CompoundTag tag) {
        this.configManager.readFromNBT(tag);
        this.patternInventory.readFromNBT(tag, NBT_MEMORY_CARD_PATTERNS);
        this.priority = tag.m_128451_(NBT_PRIORITY);
        byte unlockEventType = tag.m_128445_(NBT_UNLOCK_EVENT);
        switch (unlockEventType) {
            case 0: {
                UnlockCraftingEvent unlockCraftingEvent = null;
                break;
            }
            case 1: {
                UnlockCraftingEvent unlockCraftingEvent = UnlockCraftingEvent.REDSTONE_POWER;
                break;
            }
            case 2: {
                UnlockCraftingEvent unlockCraftingEvent = UnlockCraftingEvent.RESULT;
                break;
            }
            case 3: {
                UnlockCraftingEvent unlockCraftingEvent = UnlockCraftingEvent.REDSTONE_PULSE;
                break;
            }
            default: {
                LOGGER.error("Unknown unlock event type {} in NBT for pattern provider: {}", (Object)unlockEventType, (Object)tag);
                UnlockCraftingEvent unlockCraftingEvent = this.unlockEvent = null;
            }
        }
        if (this.unlockEvent == UnlockCraftingEvent.RESULT) {
            this.unlockStack = GenericStack.readTag((CompoundTag)tag.m_128469_(NBT_UNLOCK_STACK));
            if (this.unlockStack == null) {
                LOGGER.error("Could not load unlock stack for pattern provider from NBT: {}", (Object)tag);
            }
        } else {
            this.unlockStack = null;
        }
        ListTag sendListTag = tag.m_128437_(NBT_SEND_LIST, 10);
        for (int i = 0; i < sendListTag.size(); ++i) {
            GenericStack stack = GenericStack.readTag((CompoundTag)sendListTag.m_128728_(i));
            if (stack == null) continue;
            this.addToSendList(stack.what(), stack.amount());
        }
        if (tag.m_128441_(NBT_SEND_DIRECTION)) {
            this.sendDirection = Direction.m_122376_((int)tag.m_128445_(NBT_SEND_DIRECTION));
        }
        this.returnInv.readFromTag(tag.m_128437_(NBT_RETURN_INV, 10));
    }

    public IConfigManager getConfigManager() {
        return this.configManager;
    }

    public void saveChanges() {
        this.host.saveChanges();
    }

    public void onChangeInventory(InternalInventory inv, int slot) {
        this.saveChanges();
        this.updatePatterns();
    }

    public boolean isClientSide() {
        Level level = this.host.getBlockEntity().m_58904_();
        return level == null || level.m_5776_();
    }

    public void updatePatterns() {
        this.patterns.clear();
        this.patternInputs.clear();
        for (ItemStack stack : this.patternInventory) {
            IPatternDetails details = PatternDetailsHelper.decodePattern((ItemStack)stack, (Level)this.host.getBlockEntity().m_58904_());
            if (details == null) continue;
            this.patterns.add(details);
            for (IPatternDetails.IInput iinput : details.getInputs()) {
                for (GenericStack inputCandidate : iinput.getPossibleInputs()) {
                    this.patternInputs.add(inputCandidate.what().dropSecondary());
                }
            }
        }
        ICraftingProvider.requestUpdate((IManagedGridNode)this.mainNode);
    }

    public List<IPatternDetails> getAvailablePatterns() {
        return this.patterns;
    }

    public int getPatternPriority() {
        return this.priority;
    }

    private <T> void rearrangeRoundRobin(List<T> list) {
        if (list.isEmpty()) {
            return;
        }
        this.roundRobinIndex %= list.size();
        for (int i = 0; i < this.roundRobinIndex; ++i) {
            list.add(list.get(i));
        }
        list.subList(0, this.roundRobinIndex).clear();
    }

    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!(this.sendList.isEmpty() && this.mainNode.isActive() && this.patterns.contains(patternDetails))) {
            return false;
        }
        BlockEntity be = this.host.getBlockEntity();
        Level level = be.m_58904_();
        if (this.getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }
        ArrayList<PushTarget> possibleTargets = new ArrayList<PushTarget>();
        for (Direction direction : this.getActiveSides()) {
            BlockPos adjPos = be.m_58899_().m_121945_(direction);
            BlockEntity adjBe = level.m_7702_(adjPos);
            Direction adjBeSide = direction.m_122424_();
            ICraftingMachine craftingMachine = ICraftingMachine.of((Level)level, (BlockPos)adjPos, (Direction)adjBeSide, (BlockEntity)adjBe);
            if (craftingMachine != null && craftingMachine.acceptsPlans()) {
                if (!craftingMachine.pushPattern(patternDetails, inputHolder, adjBeSide)) continue;
                this.onPushPatternSuccess(patternDetails);
                return true;
            }
            PatternProviderTarget adapter = this.findAdapter(direction);
            if (adapter == null) continue;
            possibleTargets.add(new PushTarget(direction, adapter));
        }
        if (!patternDetails.supportsPushInputsToExternalInventory()) {
            return false;
        }
        this.rearrangeRoundRobin(possibleTargets);
        for (int i = 0; i < possibleTargets.size(); ++i) {
            PushTarget target = (PushTarget)possibleTargets.get(i);
            Direction direction = target.direction();
            PatternProviderTarget adapter = target.target();
            if (this.isBlocking() && adapter.containsPatternInput(this.patternInputs) || !this.adapterAcceptsAll(adapter, inputHolder)) continue;
            patternDetails.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                long inserted = adapter.insert(what, amount, Actionable.MODULATE);
                if (inserted < amount) {
                    this.addToSendList(what, amount - inserted);
                }
            });
            this.onPushPatternSuccess(patternDetails);
            this.sendDirection = direction;
            this.sendStacksOut();
            this.roundRobinIndex += i + 1;
            return true;
        }
        return false;
    }

    public void resetCraftingLock() {
        if (this.unlockEvent != null) {
            this.unlockEvent = null;
            this.unlockStack = null;
            this.saveChanges();
        }
    }

    private void onPushPatternSuccess(IPatternDetails pattern) {
        this.resetCraftingLock();
        LockCraftingMode lockMode = (LockCraftingMode)this.configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
        switch (1.$SwitchMap$appeng$api$config$LockCraftingMode[lockMode.ordinal()]) {
            case 1: {
                this.unlockEvent = this.getRedstoneState() ? UnlockCraftingEvent.REDSTONE_PULSE : UnlockCraftingEvent.REDSTONE_POWER;
                this.redstoneState = YesNo.UNDECIDED;
                this.saveChanges();
                break;
            }
            case 2: {
                this.unlockEvent = UnlockCraftingEvent.RESULT;
                this.unlockStack = pattern.getPrimaryOutput();
                this.saveChanges();
            }
        }
    }

    public LockCraftingMode getCraftingLockedReason() {
        LockCraftingMode lockMode = (LockCraftingMode)this.configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
        if (lockMode == LockCraftingMode.LOCK_WHILE_LOW && !this.getRedstoneState()) {
            return LockCraftingMode.LOCK_WHILE_LOW;
        }
        if (lockMode == LockCraftingMode.LOCK_WHILE_HIGH && this.getRedstoneState()) {
            return LockCraftingMode.LOCK_WHILE_HIGH;
        }
        if (this.unlockEvent != null) {
            switch (1.$SwitchMap$appeng$helpers$patternprovider$UnlockCraftingEvent[this.unlockEvent.ordinal()]) {
                case 1: 
                case 2: {
                    return LockCraftingMode.LOCK_UNTIL_PULSE;
                }
                case 3: {
                    return LockCraftingMode.LOCK_UNTIL_RESULT;
                }
            }
        }
        return LockCraftingMode.NONE;
    }

    @Nullable
    public GenericStack getUnlockStack() {
        return this.unlockStack;
    }

    private Set<Direction> getActiveSides() {
        EnumSet sides = this.host.getTargets();
        IGridNode node = this.mainNode.getNode();
        if (node != null) {
            for (Map.Entry entry : node.getInWorldConnections().entrySet()) {
                IGridNode otherNode = ((IGridConnection)entry.getValue()).getOtherSide(node);
                if (!(otherNode.getOwner() instanceof PatternProviderLogicHost) && (!(otherNode.getOwner() instanceof InterfaceLogicHost) || !otherNode.getGrid().equals(this.mainNode.getGrid()))) continue;
                sides.remove(entry.getKey());
            }
        }
        return sides;
    }

    public boolean isBlocking() {
        return this.configManager.getSetting(Settings.BLOCKING_MODE) == YesNo.YES;
    }

    @Nullable
    private PatternProviderTarget findAdapter(Direction side) {
        if (this.targetCaches[side.m_122411_()] == null) {
            BlockEntity thisBe = this.host.getBlockEntity();
            this.targetCaches[side.m_122411_()] = new PatternProviderTargetCache((ServerLevel)thisBe.m_58904_(), thisBe.m_58899_().m_121945_(side), side.m_122424_(), this.actionSource);
        }
        return this.targetCaches[side.m_122411_()].find();
    }

    private boolean adapterAcceptsAll(PatternProviderTarget target, KeyCounter[] inputHolder) {
        for (KeyCounter inputList : inputHolder) {
            for (Object2LongMap.Entry input : inputList) {
                long inserted = target.insert((AEKey)input.getKey(), input.getLongValue(), Actionable.SIMULATE);
                if (inserted != 0L) continue;
                return false;
            }
        }
        return true;
    }

    private void addToSendList(AEKey what, long amount) {
        if (amount > 0L) {
            this.sendList.add(new GenericStack(what, amount));
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
    }

    private boolean sendStacksOut() {
        if (this.sendDirection == null) {
            if (!this.sendList.isEmpty()) {
                throw new IllegalStateException("Invalid pattern provider state, this is a bug.");
            }
            return false;
        }
        PatternProviderTarget adapter = this.findAdapter(this.sendDirection);
        if (adapter == null) {
            return false;
        }
        boolean didSomething = false;
        ListIterator<GenericStack> it = this.sendList.listIterator();
        while (it.hasNext()) {
            long amount;
            GenericStack stack = it.next();
            AEKey what = stack.what();
            long inserted = adapter.insert(what, amount = stack.amount(), Actionable.MODULATE);
            if (inserted >= amount) {
                it.remove();
                didSomething = true;
                continue;
            }
            if (inserted <= 0L) continue;
            it.set(new GenericStack(what, amount - inserted));
            didSomething = true;
        }
        if (this.sendList.isEmpty()) {
            this.sendDirection = null;
        }
        return didSomething;
    }

    public boolean isBusy() {
        return !this.sendList.isEmpty();
    }

    private boolean hasWorkToDo() {
        return !this.sendList.isEmpty() || !this.returnInv.isEmpty();
    }

    private boolean doWork() {
        return this.returnInv.injectIntoNetwork(this.mainNode.getGrid().getStorageService().getInventory(), this.actionSource, this::onStackReturnedToNetwork) | this.sendStacksOut();
    }

    public InternalInventory getPatternInv() {
        return this.patternInventory;
    }

    public void onMainNodeStateChanged() {
        if (this.mainNode.isActive()) {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
    }

    public void addDrops(List<ItemStack> drops) {
        for (ItemStack itemStack : this.patternInventory) {
            drops.add(itemStack);
        }
        for (GenericStack genericStack : this.sendList) {
            genericStack.what().addDrops(genericStack.amount(), drops, this.host.getBlockEntity().m_58904_(), this.host.getBlockEntity().m_58899_());
        }
        this.returnInv.addDrops(drops, this.host.getBlockEntity().m_58904_(), this.host.getBlockEntity().m_58899_());
    }

    public void clearContent() {
        this.patternInventory.clear();
        this.sendList.clear();
        this.returnInv.clear();
    }

    public PatternProviderReturnInventory getReturnInv() {
        return this.returnInv;
    }

    public void exportSettings(CompoundTag output) {
        this.patternInventory.writeToNBT(output, NBT_MEMORY_CARD_PATTERNS);
    }

    public void importSettings(CompoundTag input, @Nullable Player player) {
        if (player != null && input.m_128441_(NBT_MEMORY_CARD_PATTERNS) && !player.m_9236_().f_46443_) {
            this.clearPatternInventory(player);
            AppEngInternalInventory desiredPatterns = new AppEngInternalInventory(this.patternInventory.size());
            desiredPatterns.readFromNBT(input, NBT_MEMORY_CARD_PATTERNS);
            Inventory playerInv = player.m_150109_();
            int blankPatternsAvailable = player.m_150110_().f_35937_ ? Integer.MAX_VALUE : playerInv.m_18947_(AEItems.BLANK_PATTERN.m_5456_());
            int blankPatternsUsed = 0;
            for (int i = 0; i < desiredPatterns.size(); ++i) {
                IPatternDetails pattern = PatternDetailsHelper.decodePattern((ItemStack)desiredPatterns.getStackInSlot(i), (Level)this.host.getBlockEntity().m_58904_(), (boolean)true);
                if (pattern == null || blankPatternsAvailable < ++blankPatternsUsed || this.patternInventory.addItems(pattern.getDefinition().toStack()).m_41619_()) continue;
                AELog.warn((String)"Failed to add pattern to pattern provider", (Object[])new Object[0]);
                --blankPatternsUsed;
            }
            if (blankPatternsUsed > 0 && !player.m_150110_().f_35937_) {
                new PlayerInternalInventory(playerInv).removeItems(blankPatternsUsed, AEItems.BLANK_PATTERN.stack(), null);
            }
            if (blankPatternsUsed > blankPatternsAvailable) {
                player.m_213846_((Component)PlayerMessages.MissingBlankPatterns.text(new Object[]{blankPatternsUsed - blankPatternsAvailable}));
            }
        }
    }

    private void clearPatternInventory(Player player) {
        if (player.m_150110_().f_35937_) {
            for (int i = 0; i < this.patternInventory.size(); ++i) {
                this.patternInventory.setItemDirect(i, ItemStack.f_41583_);
            }
            return;
        }
        Inventory playerInv = player.m_150109_();
        int blankPatternCount = 0;
        for (int i = 0; i < this.patternInventory.size(); ++i) {
            ItemStack pattern = this.patternInventory.getStackInSlot(i);
            if (pattern.m_150930_(AEItems.CRAFTING_PATTERN.m_5456_()) || pattern.m_150930_(AEItems.PROCESSING_PATTERN.m_5456_()) || pattern.m_150930_(AEItems.SMITHING_TABLE_PATTERN.m_5456_()) || pattern.m_150930_(AEItems.STONECUTTING_PATTERN.m_5456_()) || pattern.m_150930_(AEItems.BLANK_PATTERN.m_5456_())) {
                blankPatternCount += pattern.m_41613_();
            } else {
                playerInv.m_150079_(pattern);
            }
            this.patternInventory.setItemDirect(i, ItemStack.f_41583_);
        }
        if (blankPatternCount > 0) {
            playerInv.m_150076_(AEItems.BLANK_PATTERN.stack(blankPatternCount), false);
        }
    }

    private void onStackReturnedToNetwork(GenericStack genericStack) {
        if (this.unlockEvent != UnlockCraftingEvent.RESULT) {
            return;
        }
        if (this.unlockStack == null) {
            LOGGER.error("pattern provider was waiting for RESULT, but no result was set");
            this.unlockEvent = null;
        } else if (this.unlockStack.what().equals(genericStack.what())) {
            long remainingAmount = this.unlockStack.amount() - genericStack.amount();
            if (remainingAmount <= 0L) {
                this.unlockEvent = null;
                this.unlockStack = null;
            } else {
                this.unlockStack = new GenericStack(this.unlockStack.what(), remainingAmount);
            }
        }
    }

    public PatternContainerGroup getTerminalGroup() {
        Nameable nameable;
        BlockEntity host = this.host.getBlockEntity();
        Level hostLevel = host.m_58904_();
        PatternProviderLogicHost patternProviderLogicHost = this.host;
        if (patternProviderLogicHost instanceof Nameable && (nameable = (Nameable)patternProviderLogicHost).m_8077_()) {
            Component name = nameable.m_7770_();
            return new PatternContainerGroup(this.host.getTerminalIcon(), name, List.of());
        }
        Set<Direction> sides = this.getActiveSides();
        LinkedHashSet<PatternContainerGroup> groups = new LinkedHashSet<PatternContainerGroup>(sides.size());
        for (Direction direction : sides) {
            BlockPos sidePos = host.m_58899_().m_121945_(direction);
            PatternContainerGroup group = PatternContainerGroup.fromMachine((Level)hostLevel, (BlockPos)sidePos, (Direction)direction.m_122424_());
            if (group == null) continue;
            groups.add(group);
        }
        if (groups.size() == 1) {
            return (PatternContainerGroup)groups.iterator().next();
        }
        ArrayList<Object> tooltip = List.of();
        if (groups.size() > 1) {
            tooltip = new ArrayList<Object>();
            tooltip.add(GuiText.AdjacentToDifferentMachines.text().m_130940_(ChatFormatting.BOLD));
            for (PatternContainerGroup group : groups) {
                tooltip.add(group.name());
                for (Component line : group.tooltip()) {
                    tooltip.add(Component.m_237113_((String)"  ").m_7220_(line));
                }
            }
        }
        AEItemKey aEItemKey = this.host.getTerminalIcon();
        return new PatternContainerGroup(aEItemKey, aEItemKey.getDisplayName(), tooltip);
    }

    public long getSortValue() {
        BlockEntity te = this.host.getBlockEntity();
        return te.m_58899_().m_123343_() << 24 ^ te.m_58899_().m_123341_() << 8 ^ te.m_58899_().m_123342_();
    }

    public <T> LazyOptional<T> getCapability(Capability<T> capability) {
        return this.returnInv.getCapability(capability);
    }

    @Nullable
    public IGrid getGrid() {
        return this.mainNode.getGrid();
    }

    public void updateRedstoneState() {
        if (this.unlockEvent == UnlockCraftingEvent.REDSTONE_POWER && this.getRedstoneState()) {
            this.unlockEvent = null;
            this.saveChanges();
        } else if (this.unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE && !this.getRedstoneState()) {
            this.unlockEvent = UnlockCraftingEvent.REDSTONE_POWER;
            this.redstoneState = YesNo.UNDECIDED;
            this.saveChanges();
        } else {
            this.redstoneState = YesNo.UNDECIDED;
        }
    }

    private void configChanged(IConfigManager manager, Setting<?> setting) {
        if (setting == Settings.LOCK_CRAFTING_MODE) {
            this.resetCraftingLock();
        } else {
            this.saveChanges();
        }
    }

    private boolean getRedstoneState() {
        if (this.redstoneState == YesNo.UNDECIDED) {
            BlockEntity be = this.host.getBlockEntity();
            this.redstoneState = be.m_58904_().m_276867_(be.m_58899_()) ? YesNo.YES : YesNo.NO;
        }
        return this.redstoneState == YesNo.YES;
    }
}
