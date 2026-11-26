package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class DirectorVisualRework {

    private DirectorVisualRework() { }

    enum Action {
        readability, hierarchy, spacing
    }

    record Patch(String shotId, Action action, int tile, long atMs) { }

    record Sample(int frame, long atMs, String shotId) { }

    static List<Sample> samples(List<DirectorPipeline.Shot> shots, int count) {
        long frames = shots.getLast().endMs() * 30 / 1000;
        List<Sample> out = new ArrayList<>(count);
        int shotIndex = 0;
        for (int i = 0; i < count; i++) {
            int frame = Math.toIntExact(frames * (2 * i + 1) / (2 * count));
            if (i == 0) frame = Math.min(30, frame);
            long atMs = frame * 1000L / 30;
            while (shotIndex + 1 < shots.size() && atMs >= shots.get(shotIndex).endMs()) {
                shotIndex++;
            }
            out.add(new Sample(frame, atMs, shots.get(shotIndex).id()));
        }
        return List.copyOf(out);
    }

    static List<Patch> candidates(JsonNode review, List<Sample> samples) {
        JsonNode screenshot = review.path("screenshot");
        if (!"needs_review".equals(screenshot.path("verdict").asString(""))) return List.of();
        List<Patch> out = new ArrayList<>();
        for (JsonNode finding : screenshot.path("findings")) {
            String kind = finding.path("kind").asString("");
            if ("fact".equals(kind)) return List.of();
            int tile = finding.path("tile").asInt(0);
            if (tile < 1 || tile > samples.size()) continue;
            Action action = switch (kind) {
                case "readability" -> Action.readability;
                case "hierarchy" -> Action.hierarchy;
                case "spacing" -> Action.spacing;
                default -> null;
            };
            if (action == null) continue;
            Sample sample = samples.get(tile - 1);
            if (out.stream().noneMatch(patch -> patch.shotId().equals(sample.shotId()) && patch.action() == action)) {
                out.add(new Patch(sample.shotId(), action, tile, sample.atMs()));
            }
        }
        return List.copyOf(out);
    }

    static boolean accepted(JsonNode report) {
        JsonNode shot = report.path("screenshot");
        return "pass".equals(shot.path("verdict").asString("")) && shot.path("frames").asInt(0) > 0
            && shot.path("findings").isArray() && shot.path("findings").isEmpty();
    }
}
