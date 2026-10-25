package com.hypeman.yell.service;

import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.ClipTranscoder.TranscodeException;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.push.model.PushMessage;
import com.hypeman.push.service.PushService;
import com.hypeman.user.model.UserView;
import com.hypeman.user.service.UserService;
import com.hypeman.yell.model.Yell;
import com.hypeman.yell.model.YellSentEvent;
import com.hypeman.yell.repository.YellRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * After a yell is sent: checks a first send's files with ffprobe (10 seconds at most, and really audio or video), taking
 * it down if they fail, then pushes it to its new recipients. Off the request thread, one yell at a time.
 */
@Service
public class YellChecker {

    private static final Logger log = LoggerFactory.getLogger(YellChecker.class);
    /** Ten seconds, with slack for encoder padding, as for pack clips. */
    private static final long MAX_DURATION_MS = 10_500;

    private final YellRepository yells;
    private final MediaStorage media;
    private final ClipTranscoder transcoder;
    private final PushService push;
    private final UserService users;
    private final TransactionTemplate newTransaction;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("yell-checks").factory());

    YellChecker(YellRepository yells, MediaStorage media, ClipTranscoder transcoder, PushService push, UserService users,
                PlatformTransactionManager transactions) {
        this.yells = yells;
        this.media = media;
        this.transcoder = transcoder;
        this.push = push;
        this.users = users;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Only after commit, so the deliveries exist when a recipient opens the push. */
    @TransactionalEventListener
    void onSent(YellSentEvent event) {
        queue.execute(() -> process(event));
    }

    void process(YellSentEvent event) {
        try {
            Yell yell = yells.findById(event.yellId()).orElseThrow();
            if (event.firstSend() && !passesChecks(yell)) {
                return;
            }
            if (!event.recipientIds().isEmpty()) {
                push.send(event.recipientIds(), message(yell));
            }
        } catch (RuntimeException e) {
            log.error("Could not finish sending yell {}", event.yellId(), e);
        }
    }

    /** False when the yell was taken down. A check that couldn't run lets it through: the sender did nothing wrong. */
    private boolean passesChecks(Yell yell) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("yell-");
            Probe audio = probe(yell.getAudioKey(), workDir.resolve("audio"));
            if (!audio.hasAudio() || audio.durationMs() > MAX_DURATION_MS) {
                return takeDown(yell, "audio of " + audio.durationMs() + " ms");
            }
            if (yell.getVideoKey() != null) {
                Probe video = probe(yell.getVideoKey(), workDir.resolve("video"));
                if (!video.hasVideo() || video.durationMs() > MAX_DURATION_MS) {
                    return takeDown(yell, "video of " + video.durationMs() + " ms");
                }
            }
            newTransaction.executeWithoutResult(status -> yells.findById(yell.getId()).ifPresent(found -> found.recordDuration((int) audio.durationMs())));
            return true;
        } catch (TranscodeException e) {
            return takeDown(yell, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("Could not check yell {}; sending it unchecked", yell.getId(), e);
            return true;
        } finally {
            deleteQuietly(workDir);
        }
    }

    private Probe probe(String key, Path dir) throws IOException {
        Files.createDirectory(dir);
        Path file = dir.resolve("source");
        media.download(key, file);
        return transcoder.probe(file);
    }

    private boolean takeDown(Yell yell, String reason) {
        log.info("Took down yell {}: {}", yell.getId(), reason);
        newTransaction.executeWithoutResult(status -> yells.findById(yell.getId()).ifPresent(Yell::remove));
        return false;
    }

    private PushMessage message(Yell yell) {
        UserView sender = users.views(List.of(yell.getSenderId())).get(yell.getSenderId());
        String name = sender == null || sender.displayName() == null ? "A friend" : sender.displayName();
        return new PushMessage(name + " yelled at you", "“" + yell.getCaption() + "”", Map.of("yellId", yell.getId().toString()));
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete {}", dir, e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        queue.shutdown();
        queue.awaitTermination(30, TimeUnit.SECONDS);
    }
}
