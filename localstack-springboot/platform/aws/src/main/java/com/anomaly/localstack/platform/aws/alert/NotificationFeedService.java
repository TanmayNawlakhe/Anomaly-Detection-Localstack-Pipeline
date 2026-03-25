package com.anomaly.localstack.platform.aws.alert;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class NotificationFeedService {

    private static final int MAX_NOTIFICATIONS = 100;

    private final Deque<Map<String, String>> notifications = new ConcurrentLinkedDeque<>();

    public void addNotification(String adminEmail, String message) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("delivered_at", Instant.now().toString());
        entry.put("target", adminEmail);
        entry.put("message", message);

        notifications.addFirst(entry);
        while (notifications.size() > MAX_NOTIFICATIONS) {
            notifications.pollLast();
        }
    }

    public List<Map<String, String>> latest(int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_NOTIFICATIONS));
        List<Map<String, String>> output = new ArrayList<>(bounded);
        int count = 0;
        for (Map<String, String> item : notifications) {
            if (count >= bounded) {
                break;
            }
            output.add(item);
            count++;
        }
        return output;
    }

    public void clear() {
        notifications.clear();
    }
}