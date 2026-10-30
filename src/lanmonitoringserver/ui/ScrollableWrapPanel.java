package lanmonitoringserver.ui;

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

final class ScrollableWrapPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;

    ScrollableWrapPanel(WrapLayout layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRectangle, int orientation, int direction) {
        return 18;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRectangle, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
                ? Math.max(18, visibleRectangle.height - 36)
                : Math.max(18, visibleRectangle.width - 36);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() { return true; }

    @Override
    public boolean getScrollableTracksViewportHeight() { return false; }
}
