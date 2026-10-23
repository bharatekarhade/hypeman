package com.hypeman.pack.service;

import com.hypeman.media.service.ClipTranscoder;
import com.hypeman.media.service.ClipTranscoder.Outputs;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.ClipTranscoder.TranscodeException;
import com.hypeman.media.service.MediaStorage;
import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipUploadedEvent;
import com.hypeman.pack.repository.ClipRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes each uploaded clip once its upload is confirmed: the original is downloaded, checked, converted, and the
 * results uploaded beside it. One clip at a time, off the request thread, since ffmpeg is CPU-heavy and the server is
 * small. The clip ends READY or FAILED with a reason the uploader can act on.
 */
@Service
public class ClipProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClipProcessor.class);
    /** The app's ClipRole.maxDuration is 10 seconds; the slack covers encoder padding. */
    private static final long MAX_DURATION_MS = 10_500;

    private final ClipRepository clipRepository;
    private final MediaStorage mediaStorage;
    private final ClipTranscoder transcoder;
    private final TransactionTemplate newTransaction;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("clip-processing").factory());

    ClipProcessor(ClipRepository clipRepository, MediaStorage mediaStorage, ClipTranscoder transcoder, PlatformTransactionManager transactions) {
        this.clipRepository = clipRepository;
        this.mediaStorage = mediaStorage;
        this.transcoder = transcoder;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Only after commit, so the queue never sees a clip whose PROCESSING status was rolled back. */
    @TransactionalEventListener
    void onUploaded(ClipUploadedEvent event) {
        queue.execute(() -> process(event.clipId(), event.sourceKey()));
    }

    void process(UUID clipId, String sourceKey) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("clip-");
            Path source = workDir.resolve("source");
            mediaStorage.download(sourceKey, source);
            Probe probe = transcoder.probe(source);
            if (!probe.hasVideo()) {
                throw new TranscodeException("That file has no video. Upload an MP4 or MOV video.", null);
            }
            if (!probe.hasAudio()) {
                throw new TranscodeException("That video has no sound. A clip needs someone yelling in it.", null);
            }
            if (probe.durationMs() > MAX_DURATION_MS) {
                throw new TranscodeException("Clips can be up to 10 seconds; this one is " + Math.round(probe.durationMs() / 1000.0) + ".", null);
            }
            Outputs outputs = transcoder.transcode(source, workDir, probe.durationMs());
            String videoKey = processedKey(sourceKey, "video", "mp4");
            String audioKey = processedKey(sourceKey, "audio", "m4a");
            String alarmKey = processedKey(sourceKey, "alarm", "caf");
            String thumbKey = processedKey(sourceKey, "thumb", "jpg");
            mediaStorage.upload(videoKey, outputs.video(), "video/mp4");
            mediaStorage.upload(audioKey, outputs.audio(), "audio/mp4");
            mediaStorage.upload(alarmKey, outputs.alarm(), "audio/x-caf");
            mediaStorage.upload(thumbKey, outputs.thumb(), "image/jpeg");
            finish(clipId, sourceKey, clip -> clip.markReady(videoKey, audioKey, alarmKey, thumbKey, (int) probe.durationMs()));
        } catch (TranscodeException e) {
            finish(clipId, sourceKey, clip -> clip.markFailed(e.getMessage()));
        } catch (RuntimeException | IOException e) {
            log.error("Processing clip {} failed", clipId, e);
            finish(clipId, sourceKey, clip -> clip.markFailed("Processing failed on our side. Complete the upload again to retry."));
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** Applies the outcome only if the clip still has this original: a newer upload makes this run moot. */
    private void finish(UUID clipId, String sourceKey, Consumer<Clip> outcome) {
        try {
            newTransaction.executeWithoutResult(status -> clipRepository.findById(clipId)
                    .filter(clip -> sourceKey.equals(clip.getSourceKey()))
                    .ifPresent(outcome));
        } catch (RuntimeException e) {
            log.error("Could not record the processing result for clip {}", clipId, e);
        }
    }

    /** packs/P/start/source-A.mov becomes packs/P/start/video-A.mp4, so each upload attempt's files stay together. */
    static String processedKey(String sourceKey, String kind, String extension) {
        int slash = sourceKey.lastIndexOf('/');
        String attempt = sourceKey.substring(slash + 1).replaceFirst("^source-", "").replaceFirst("\\.[^.]+$", "");
        return sourceKey.substring(0, slash + 1) + kind + "-" + attempt + "." + extension;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete {}", dir, e);
        }
    }

    /** Lets the clip in progress finish on a normal shutdown; anything still queued is retried by completing again. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        queue.shutdown();
        queue.awaitTermination(30, TimeUnit.SECONDS);
    }
}
