package cat.x1chi.timelimit;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/** Horizontal pager: swipe left/right (or tap a tab) to switch pages. */
final class Pager extends HorizontalScrollView {
    interface OnPage { void on(int page); }

    private final int pageW;
    private final int count;
    private int page = 0;
    private int flingV = 0;
    private OnPage listener;

    Pager(Context c, int pageW, int count) {
        super(c);
        this.pageW = pageW;
        this.count = count;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
    }

    void setOnPage(OnPage l) { listener = l; }

    void goTo(int p) {
        page = Math.max(0, Math.min(count - 1, p));
        smoothScrollTo(page * pageW, 0);
        if (listener != null) listener.on(page);
    }

    /** No free-wheeling: remember the direction and snap to a page instead. */
    @Override
    public void fling(int velocityX) {
        flingV = velocityX;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            int diff = getScrollX() - page * pageW;
            int target = page;
            if (flingV > 600 || diff > pageW * 0.15f) target = page + 1;
            else if (flingV < -600 || diff < -pageW * 0.15f) target = page - 1;
            flingV = 0;
            goTo(target);
            return true;
        }
        return r;
    }
}
