package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 过期尝试目录的只读候选清单；不删除文件，失败证据与原型目录一律跳过。
 */
@Service
public class ProductionCleanupService {

    private static final int MAX_CANDIDATES = 200;

    private final HeronProductionTaskMapper taskMapper;
    private final HeronProductionProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionCleanupService(HeronProductionTaskMapper taskMapper,
                                    HeronProductionProperties properties) {
        this(taskMapper, properties, Clock.systemUTC());
    }

    public ProductionCleanupService(HeronProductionTaskMapper taskMapper,
                                    HeronProductionProperties properties,
                                    Clock clock) {
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public List<String> dryRun() {
        return dryRun(Duration.ofDays(properties.getCleanupRetentionDays()));
    }

    public List<String> dryRun(Duration retention) {
        Path root = properties.getDataRoot().toAbsolutePath().normalize();
        Path lane = root.resolve("work").resolve("news_video");
        if (!Files.isDirectory(lane, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Instant threshold = Instant.now(clock).minus(retention);
        List<String> candidates = new ArrayList<>();
        try (Stream<Path> taskDirectories = Files.list(lane)) {
            taskDirectories
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().chars().allMatch(Character::isDigit))
                .forEach(taskDirectory -> collect(taskDirectory, threshold, root, candidates));
        } catch (IOException e) {
            throw new IllegalStateException("扫描清理候选失败", e);
        }
        candidates.sort(Comparator.naturalOrder());
        return candidates.size() > MAX_CANDIDATES ? List.copyOf(candidates.subList(0, MAX_CANDIDATES)) : List.copyOf(candidates);
    }

    private void collect(Path taskDirectory, Instant threshold, Path root, List<String> candidates) {
        HeronProductionTask task = taskMapper.selectById(Long.parseLong(taskDirectory.getFileName().toString()));
        if (task == null || !ProductionStatus.cancelled.name().equals(task.getStatus())) {
            return;
        }
        try (Stream<Path> attempts = Files.list(taskDirectory)) {
            attempts
                .filter(path -> path.getFileName().toString().startsWith("a"))
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.exists(path.resolve(".keep"), LinkOption.NOFOLLOW_LINKS))
                .filter(path -> lastModifiedBefore(path, threshold))
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .forEach(candidates::add);
        } catch (IOException e) {
            throw new IllegalStateException("扫描尝试目录失败", e);
        }
    }

    private static boolean lastModifiedBefore(Path path, Instant threshold) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(threshold);
        } catch (IOException e) {
            return false;
        }
    }
}
