package io.github.billstark001.latticium.mc.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/** Native scrollable, keyboard-navigable text rows with narration and wrapped diagnostics. */
final class UiTextList extends ObjectSelectionList<UiTextList.Row> {
  private List<Component> lines = List.of();

  UiTextList(Minecraft minecraft, int width, int height, int top, int left) {
    super(minecraft, width, height, top, 18);
    setX(left);
    centerListVertically = false;
  }

  void update(List<Component> next) {
    if (lines.equals(next)) return;
    double scroll = scrollAmount();
    lines = List.copyOf(next);
    clearEntries();
    for (var line : next) {
      int height = Math.max(18, minecraft.font.split(line, getRowWidth() - 12).size() * 12 + 6);
      addEntry(new Row(line), height);
    }
    setScrollAmount(scroll);
  }

  @Override
  public int getRowWidth() {
    return getWidth() - 16;
  }

  final class Row extends ObjectSelectionList.Entry<Row> {
    private final Component text;

    Row(Component text) {
      this.text = text;
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
      graphics.textWithWordWrap(
          minecraft.font, text, getContentX(), getContentY(), getContentWidth(), 0xFFFFFFFF);
    }

    @Override
    public Component getNarration() {
      return text;
    }
  }
}
