package com.eintswavex.wastesorter;

import java.util.ArrayList;
import java.util.List;

public class ProbabilityHistory {
    private static ProbabilityHistory instance;
    private final List<Float> plasticHistory = new ArrayList<>();
    private final List<Float> paperHistory = new ArrayList<>();
    private final List<Float> metalHistory = new ArrayList<>();
    private final List<HistoryUpdateListener> listeners = new ArrayList<>();
    private long lastUpdateTime = 0;
    private static final long MIN_UPDATE_INTERVAL = 100; // Minimum 100ms between update

    public interface HistoryUpdateListener {
        void onHistoryUpdated();
    }

    private ProbabilityHistory() {}

    public static synchronized ProbabilityHistory getInstance() {
        if (instance == null) {
            instance = new ProbabilityHistory();
        }
        return instance;
    }

    public void addData(float plastic, float paper, float metal) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL) {
            return;
        }

        synchronized (this) {
            plasticHistory.add(plastic);
            paperHistory.add(paper);
            metalHistory.add(metal);

            int maxHistorySize = 100;
            if (plasticHistory.size() > maxHistorySize) {
                plasticHistory.remove(0);
                paperHistory.remove(0);
                metalHistory.remove(0);
            }

            lastUpdateTime = currentTime;

            // Notify listeners
            notifyListeners();
        }
    }

    public void clear() {
        synchronized (this) {
            plasticHistory.clear();
            paperHistory.clear();
            metalHistory.clear();
            notifyListeners();
        }
    }

    public List<Float> getPlasticHistory() {
        return new ArrayList<>(plasticHistory);
    }

    public List<Float> getPaperHistory() {
        return new ArrayList<>(paperHistory);
    }

    public List<Float> getMetalHistory() {
        return new ArrayList<>(metalHistory);
    }

    public void addListener(HistoryUpdateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(HistoryUpdateListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (HistoryUpdateListener listener : listeners) {
            listener.onHistoryUpdated();
        }
    }
}