package dev.mwkov.spoilershield;

/**
 * Text that gives up if a pattern takes too long on it. Some patterns (nested repeats)
 * can take seconds on long text; without this one bad keyword could freeze scanning.
 */
final class TimedText implements CharSequence {

    static final class TimeUp extends RuntimeException {
        TimeUp() {
            super(null, null, false, false);
        }
    }

    private final CharSequence s;
    private final long deadlineNs;
    private int calls;

    TimedText(CharSequence s, long budgetMs) {
        this(s, System.nanoTime() + budgetMs * 1_000_000L, true);
    }

    private TimedText(CharSequence s, long deadlineNs, boolean absolute) {
        this.s = s;
        this.deadlineNs = deadlineNs;
    }

    @Override
    public char charAt(int i) {
        if ((++calls & 0x3FF) == 0 && System.nanoTime() > deadlineNs) throw new TimeUp();
        return s.charAt(i);
    }

    @Override
    public int length() {
        return s.length();
    }

    @Override
    public CharSequence subSequence(int a, int b) {
        return new TimedText(s.subSequence(a, b), deadlineNs, true);
    }

    @Override
    public String toString() {
        return s.toString();
    }
}
