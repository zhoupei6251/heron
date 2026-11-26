package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 从首屏运动代表帧生成原比例封面与平台方图。 */
final class NewsCover {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int THUMB_SIZE = 1080;
    private static final int CANDIDATES = 5;

    private NewsCover() { }

    record Result(Path cover, Path thumb, NewsProductionPipeline.Gate gate) { }

    static Result create(ProductionProcessService processes, ProductionRunVo run, Path work, Path finalFile,
                         int width, int height, NewsNarrationTiming.Span first) {
        return create(processes, run, work, finalFile, width, height, first, work);
    }

    static Result create(ProductionProcessService processes, ProductionRunVo run, Path work, Path finalFile,
                         int width, int height, NewsNarrationTiming.Span first, Path outputDirectory) {
        int[] size = sizeOf(width, height);
        List<Long> times = candidateTimes(first);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < times.size(); index++) {
            Path frame = outputDirectory.resolve("cover-candidate-" + (index + 1) + ".png");
            extract(processes, run, work, finalFile, frame, times.get(index), "scale=360:-2");
            candidates.add(new Candidate(times.get(index), frame));
        }
        int selected = select(candidates);
        long atMs = candidates.get(selected).atMs();
        Path cover = outputDirectory.resolve("cover.png");
        Path thumb = outputDirectory.resolve("cover-thumb.png");
        extract(processes, run, work, finalFile, cover, atMs, null);
        int side = Math.min(size[0], size[1]);
        String crop = "crop=" + side + ":" + side + ":(iw-" + side + ")/2:(ih-" + side + ")/2,scale="
            + THUMB_SIZE + ":" + THUMB_SIZE;
        extract(processes, run, work, cover, thumb, -1, crop);
        try {
            BufferedImage image = ImageIO.read(cover.toFile());
            BufferedImage square = ImageIO.read(thumb.toFile());
            boolean nonBlank = image != null && square != null
                && NewsProductionPipeline.blankCells(image, 1, 1) == 0
                && NewsProductionPipeline.blankCells(square, 1, 1) == 0;
            if (image == null || square == null || image.getWidth() != size[0] || image.getHeight() != size[1]
                || square.getWidth() != THUMB_SIZE || square.getHeight() != THUMB_SIZE || !nonBlank) {
                throw new HeronBusinessException(409, "news_cover_invalid",
                    "封面缺失、尺寸不符或是纯色纯黑帧：expected=" + size[0] + "x" + size[1]);
            }
            Map<String, Object> measured = new TreeMap<>();
            measured.put("atMs", atMs);
            measured.put("startMs", first.startMs());
            measured.put("endMs", first.endMs());
            measured.put("width", image.getWidth());
            measured.put("height", image.getHeight());
            measured.put("thumbWidth", square.getWidth());
            measured.put("thumbHeight", square.getHeight());
            measured.put("motion", motion(candidates.get(selected), candidates.get(Math.max(0, selected - 1))));
            measured.put("nonBlank", true);
            return new Result(cover, thumb, new NewsProductionPipeline.Gate("cover", "final.mp4#first-frame",
                "封面取自首屏运动代表帧，原比例与方图尺寸正确且不是纯色纯黑帧", measured));
        } catch (IOException e) {
            throw new HeronBusinessException(409, "news_cover_invalid", "封面图片不可读", e);
        }
    }

    private static List<Long> candidateTimes(NewsNarrationTiming.Span first) {
        if (first == null || first.endMs() <= first.startMs()) {
            throw new HeronBusinessException(409, "news_cover_invalid", "首屏没有可用于封面的实测时间窗");
        }
        long midpoint = (first.startMs() + first.endMs()) / 2;
        long low = Math.max(first.startMs(), midpoint - 600);
        long high = Math.min(first.endMs() - 1, midpoint + 600);
        LinkedHashSet<Long> times = new LinkedHashSet<>();
        for (int index = 0; index < CANDIDATES; index++) {
            times.add(low + (high - low) * index / (CANDIDATES - 1));
        }
        return new ArrayList<>(times);
    }

    private static int select(List<Candidate> candidates) {
        int midpoint = candidates.size() / 2;
        int selected = midpoint;
        double best = -1;
        for (int index = 0; index + 1 < candidates.size(); index++) {
            double motion = motion(candidates.get(index + 1), candidates.get(index));
            if (motion > best + 0.001) {
                best = motion;
                selected = index + 1;
            }
        }
        return selected;
    }

    private static double motion(Candidate current, Candidate previous) {
        try {
            BufferedImage left = ImageIO.read(previous.frame.toFile());
            BufferedImage right = ImageIO.read(current.frame.toFile());
            if (left == null || right == null || left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
                throw new HeronBusinessException(409, "news_cover_invalid", "封面候选帧尺寸不一致");
            }
            double total = 0;
            long count = 0;
            for (int y = 0; y < left.getHeight(); y += 2) {
                for (int x = 0; x < left.getWidth(); x += 2) {
                    total += difference(left.getRGB(x, y), right.getRGB(x, y));
                    count++;
                }
            }
            return count == 0 ? 0 : total / count;
        } catch (IOException e) {
            throw new HeronBusinessException(409, "news_cover_invalid", "封面候选帧不可读", e);
        }
    }

    private static int difference(int left, int right) {
        int lr = (left >> 16) & 0xFF;
        int lg = (left >> 8) & 0xFF;
        int lb = left & 0xFF;
        int rr = (right >> 16) & 0xFF;
        int rg = (right >> 8) & 0xFF;
        int rb = right & 0xFF;
        return Math.abs(lr - rr) + Math.abs(lg - rg) + Math.abs(lb - rb);
    }

    private static int[] sizeOf(int width, int height) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw new HeronBusinessException(409, "news_cover_invalid", "封面画布尺寸声明不合法");
        }
        return new int[]{width, height};
    }

    private static void extract(ProductionProcessService processes, ProductionRunVo run, Path work, Path input,
                                Path output, long atMs, String filter) {
        List<String> args = new ArrayList<>(List.of("-y", "-loglevel", "error"));
        if (atMs >= 0) {
            args.addAll(List.of("-ss", fmt(atMs / 1000.0)));
        }
        args.addAll(List.of("-i", work.relativize(input.toAbsolutePath().normalize()).toString().replace('\\', '/')));
        if (filter != null) {
            args.addAll(List.of("-vf", filter));
        }
        args.addAll(List.of("-frames:v", "1", work.relativize(output).toString().replace('\\', '/')));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg", args, work, TIMEOUT));
        if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
            throw new HeronBusinessException(503, "news_cover_failed", "封面抽帧或裁切失败");
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private record Candidate(long atMs, Path frame) { }
}
