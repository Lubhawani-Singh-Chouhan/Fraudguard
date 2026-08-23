package com.fraudguard.consumer.service;

import com.fraudguard.consumer.model.TransactionEvent;
import com.fraudguard.consumer.model.UserFeatures;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Tiny "feature store" on Redis. Per user we keep:
 * <ul>
 *   <li>a hash of running count/sum/sum-of-squares (for avg + stddev)</li>
 *   <li>a set of known device ids</li>
 *   <li>a sorted set of recent tx timestamps (5-minute velocity)</li>
 * </ul>
 * Features are read <em>before</em> the current transaction is written, so the
 * scorer sees history, not the event it is currently judging.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureStoreService {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public UserFeatures snapshot(TransactionEvent tx) {
        String userId = tx.getUserId();
        Map<Object, Object> stats = redis.opsForHash().entries(statsKey(userId));

        long txCount = parseLong(stats.get("tx_count"));
        double sum = parseDouble(stats.get("amount_sum"));
        double sumSq = parseDouble(stats.get("amount_sq_sum"));
        double avg = txCount > 0 ? sum / txCount : 0.0;
        double variance = txCount > 1 ? Math.max(0.0, (sumSq / txCount) - (avg * avg)) : 0.0;

        Instant now = Instant.now();
        long windowStart = now.minus(WINDOW).toEpochMilli();
        redis.opsForZSet().removeRangeByScore(windowKey(userId), 0, windowStart);
        Long windowSize = redis.opsForZSet().zCard(windowKey(userId));

        Boolean knownDevice = Boolean.TRUE.equals(
                redis.opsForSet().isMember(devicesKey(userId), tx.getDeviceId()));

        return UserFeatures.builder()
                .txCount(txCount)
                .avgAmount(avg)
                .stdAmount(Math.sqrt(variance))
                .txLast5m(windowSize == null ? 0 : windowSize.intValue())
                .newDevice(tx.getDeviceId() != null && !knownDevice)
                .lastTxTs((String) stats.get("last_tx_ts"))
                .build();
    }

    public void record(TransactionEvent tx) {
        String userId = tx.getUserId();
        double amount = tx.getAmount() == null ? 0.0 : tx.getAmount().doubleValue();

        redis.opsForHash().increment(statsKey(userId), "tx_count", 1);
        redis.opsForHash().increment(statsKey(userId), "amount_sum", amount);
        redis.opsForHash().increment(statsKey(userId), "amount_sq_sum", amount * amount);
        redis.opsForHash().put(statsKey(userId), "last_tx_ts", tx.getTimestamp());

        if (tx.getDeviceId() != null) {
            redis.opsForSet().add(devicesKey(userId), tx.getDeviceId());
        }

        long now = Instant.now().toEpochMilli();
        redis.opsForZSet().add(windowKey(userId), tx.getTransactionId() + ":" + now, now);
        redis.expire(windowKey(userId), Duration.ofMinutes(15));
    }

    private static String statsKey(String userId) {
        return "user:" + userId + ":stats";
    }

    private static String devicesKey(String userId) {
        return "user:" + userId + ":devices";
    }

    private static String windowKey(String userId) {
        return "user:" + userId + ":window";
    }

    private static long parseLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDouble(Object raw) {
        if (raw == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
