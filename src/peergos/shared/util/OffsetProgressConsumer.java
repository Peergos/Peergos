package peergos.shared.util;

public final class OffsetProgressConsumer implements ProgressConsumer<Long> {

    private ProgressConsumer consumer;
    private final int maxLength;
    private int currentCount;

    public OffsetProgressConsumer(ProgressConsumer<Long> consumer, int maxLength) {
        this.consumer = consumer;
        this.maxLength = maxLength;
        this.currentCount = 0;
    }
    public void accept(Long delta) {
        int startValue = currentCount;
        currentCount += delta.intValue();
        currentCount = Math.min(currentCount, maxLength);
        int diff = currentCount - startValue;
        consumer.accept((long)diff);
    }
}