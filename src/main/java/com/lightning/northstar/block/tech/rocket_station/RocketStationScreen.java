package com.lightning.northstar.block.tech.rocket_station;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.accessor.NorthstarLevel;
import com.lightning.northstar.block.tech.rocket_station.RocketStationMenu.DimensionEntry;
import com.lightning.northstar.client.gui.ListScrollInput;
import com.lightning.northstar.client.gui.ScrollingLabel;
import com.lightning.northstar.compat.jei.NorthstarJEI;
import com.lightning.northstar.compat.jei.category.FuelTypeCategory;
import com.lightning.northstar.compat.jei.category.HeatShieldingCategory;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.content.NorthstarBlocks;
import com.lightning.northstar.contraption.FuelType;
import com.lightning.northstar.contraption.rocket.*;
import com.lightning.northstar.data.ModCompat;
import com.lightning.northstar.planet.Planet;
import com.lightning.northstar.planet.data.PlanetProperties;
import com.lightning.northstar.util.NorthstarLang;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.floats.Float2ObjectFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.lang.LangNumberFormat;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

@OnlyIn(Dist.CLIENT)
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketStationScreen extends AbstractSimiContainerScreen<RocketStationMenu> {

    public static final ResourceLocation TEXTURE = Northstar.asResource("textures/gui/rocket_station.png");

    private IconButton button1;
    private IconButton button2;
    private ListScrollInput<DimensionEntry> dimensionSelection;
    private ListScrollInput<Pair<RocketDestination, Component>> destinationSelection;
    private ContainerListener slotChangeListener;
    private List<Component> messages;
    private int ticks;

    public RocketStationScreen(RocketStationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);

        setWindowSize(176, 210);
    }

    @Override
    protected void init() {
        super.init();

        RocketStationHolder holder = menu.contentHolder;
        if (holder == null) {
            onClose();
            return;
        }

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        Label dimensionLabel = new ScrollingLabel(x + 30, y + 11, 110, 10, Component.empty())
                .withShadow();

        dimensionSelection = new ListScrollInput<>(x + 30, y + 8, 115, 18);
        dimensionSelection.formatter(DimensionEntry::text)
                .calling(i -> {
                    destinationSelection.options(dimensionSelection.get().destinations());
                    updateInfo();
                })
                .writingTo(dimensionLabel);
        addRenderableWidget(dimensionSelection);
        addRenderableWidget(dimensionLabel);

        Label destionationLabel = new ScrollingLabel(x + 30, y + 30, 110, 10, Component.empty())
                .withShadow();

        destinationSelection = new ListScrollInput<>(x + 30, y + 27, 115, 18);
        destinationSelection.formatter(Pair::second)
                .writingTo(destionationLabel);
        addRenderableWidget(destinationSelection);
        addRenderableWidget(destionationLabel);

        button1 = new IconButton(x + 151, y + 7, AllIcons.I_NONE);
        addRenderableWidget(button1);

        button2 = new IconButton(x + 151, y + 7 + 18 + 1, AllIcons.I_NONE);
        addRenderableWidget(button2);

        button1.setIcon(AllIcons.I_CONFIRM);
        button1.setToolTip(Component.translatable("northstar.gui.rocket_station.assemble"));
        button1.withCallback(() -> {
            if (button1.active) {
                saveSettings(true);
                removed();
                onClose();
            }
        });

        button2.setIcon(AllIcons.I_CONFIG_SAVE);
        button2.setToolTip(Component.translatable("northstar.gui.rocket_station.save"));
        button2.withCallback(() -> {
            saveSettings(false);
            removed();
            onClose();
        });

        slotChangeListener = container -> {
            List<DimensionEntry> options = new ArrayList<>(RocketStationMenu.getPossibleDestinations(NorthstarLevel.CLIENT_TRACKER, container.getItem(0)));
            options.sort(Comparator.comparing(dim -> dim.text().getString()));

            RocketDestination destination = holder.be().destination;

            if (options.isEmpty()) {
                dimensionSelection.options(List.of(new DimensionEntry(null, null, List.of(), Component.empty())));
                dimensionSelection.onChanged();
                destinationSelection.onChanged();
            } else {
                DimensionEntry dim = options.stream()
                        .filter(entry -> destination != null && Objects.equals(entry.dimensionId(), destination.dim()))
                        .findFirst()
                        .orElse(null);

                dimensionSelection.options(options);
                dimensionSelection.setState(dim);
                dimensionSelection.onChanged();
                if (dim != null) {
                    Pair<RocketDestination, Component> pos = dim.destinations()
                            .stream()
                            .filter(pair -> Objects.equals(pair.first(), destination))
                            .findFirst()
                            .orElse(null);
                    destinationSelection.setState(pos);
                }
                destinationSelection.onChanged();
            }

            dimensionSelection.active = !options.isEmpty();
            destinationSelection.active = !options.isEmpty();
        };
        holder.container().addListener(slotChangeListener);

        // initially update the container to display everything properly
        slotChangeListener.containerChanged(holder.container());
    }

    @Override
    public void onClose() {
        super.onClose();

        menu.contentHolder.container().removeListener(slotChangeListener);
    }

    private void saveSettings(boolean toggleAssembly) {
        CatnipServices.NETWORK.sendToServer(new RocketStationEditPacket(
                menu.contentHolder.pos(),
                toggleAssembly,
                destinationSelection.get() == null ? null : destinationSelection.get().first()
        ));
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        if (ticks++ >= 20) {
            ticks = 0;
            updateInfo();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        GuiGameElement.of(NorthstarBlocks.ROCKET_WAYPOINT)
                .at(x + 8, y + 27)
                .render(graphics);

        if (messages == null) {
            updateInfo();
        }

        int labelY = y + 48;
        for (Component line : messages) {
            graphics.drawString(font, line, x + 8, labelY, 0xFFFFFF, true);
            labelY += 12;
        }

    }

    @Override
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);

        graphics.renderComponentHoverEffect(font, getComponentStyleAt(mouseX, mouseY), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleComponentClicked(getComponentStyleAt((int) mouseX, (int) mouseY))) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean handleComponentClicked(@Nullable Style style) {
        if (style != null && style.getClickEvent() != null && style.getClickEvent().getAction() == ClickEvent.Action.CHANGE_PAGE) {
            ModCompat.JEI.executeIfLoaded(() -> () -> {
                switch (style.getClickEvent().getValue()) {
                    case "heat_shielding" ->
                            NorthstarJEI.getRuntime().getRecipesGui().showTypes(List.of(HeatShieldingCategory.RECIPE_TYPE));
                    case "fuel" ->
                            NorthstarJEI.getRuntime().getRecipesGui().showTypes(List.of(FuelTypeCategory.RECIPE_TYPE));
                }
            });
            return true;
        }

        return super.handleComponentClicked(style);
    }

    @Nullable
    private Style getComponentStyleAt(int mouseX, int mouseY) {
        int x = mouseX - leftPos - 8;
        int y = (mouseY - topPos - 48) / 12;
        return mouseY <= topPos + 46 || y < 0 || y >= messages.size() || x < 0 ? null : font.getSplitter().componentStyleAtWidth(messages.get(y), x);
    }

    private void updateInfo() {
        ClientLevel level = Minecraft.getInstance().level;
        RocketContraption contraption = menu.contentHolder.contraption();
        DimensionEntry target = dimensionSelection.get();
        messages = List.of(
                Component.literal("Blocks: " + contraption.blocks.size()),
                Component.literal("Thrusters: " + contraption.thrusterPositions.size()),
                Component.literal("Fuel tanks: " + contraption.fuelTankPositions.size()),
                Component.literal("Seats: " + contraption.seatPositions.size()),
                Component.literal("Mass rating: " + (int) contraption.massWeight),
                Component.literal(contraption.hasControls ? "Flight controls ready" : "Missing flight controls")
        );
    }

}
