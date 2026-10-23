package com.hypeman.media.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an uploaded video into what the app plays, by running ffprobe and ffmpeg (both must be on the PATH). Loudness is
 * normalized everywhere, so every pack yells about as loud as the next.
 */
@Service
public class ClipTranscoder {

    private static final Logger log = LoggerFactory.getLogger(ClipTranscoder.class);
    private static final long TIMEOUT_SECONDS = 120;
    /** Streaming loudness, with headroom so a shout doesn't clip. */
    private static final String LOUDNORM = "loudnorm=I=-14:TP=-1.5:LRA=11";

    private final JsonMapper json;

    ClipTranscoder(JsonMapper json) {
        this.json = json;
    }

    /** What's in the file, without converting anything. */
    public Probe probe(Path source) {
        Path report = source.resolveSibling("probe.json");
        run(List.of("ffprobe", "-v", "error", "-print_format", "json", "-show_entries", "format=duration:stream=codec_type",
                source.toString()), report, "We couldn't read that file. Upload an MP4 or MOV video.");
        try {
            JsonNode root = json.readTree(Files.readString(report));
            boolean video = false;
            boolean audio = false;
            for (JsonNode stream : root.path("streams")) {
                video |= "video".equals(stream.path("codec_type").asString());
                audio |= "audio".equals(stream.path("codec_type").asString());
            }
            long durationMs = Math.round(Double.parseDouble(root.path("format").path("duration").asString("0")) * 1000);
            return new Probe(durationMs, video, audio);
        } catch (IOException | JacksonException | NumberFormatException e) {
            throw new TranscodeException("We couldn't read that file. Upload an MP4 or MOV video.", e);
        }
    }

    /**
     * Writes video.mp4 (H.264 and AAC, at most 1280 tall, ready to stream), audio.m4a, alarm.caf (16-bit PCM, which iOS
     * takes as a notification sound) and thumb.jpg into {@code outputDir}.
     *
     * @param durationMs from {@link #probe}, to take the thumbnail from the middle of short clips
     */
    public Outputs transcode(Path source, Path outputDir, long durationMs) {
        String failed = "We couldn't convert that video. Try exporting it again as an MP4.";
        Path video = outputDir.resolve("video.mp4");
        Path audio = outputDir.resolve("audio.m4a");
        Path alarm = outputDir.resolve("alarm.caf");
        Path thumb = outputDir.resolve("thumb.jpg");
        Path ffmpegLog = outputDir.resolve("ffmpeg.log");
        run(ffmpeg(source, "-map", "0:v:0", "-map", "0:a:0", "-vf", "scale=-2:'min(1280,trunc(ih/2)*2)'",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-af", LOUDNORM, "-c:a", "aac", "-b:a", "128k", "-ar", "48000",
                "-movflags", "+faststart", "-map_metadata", "-1", video.toString()), ffmpegLog, failed);
        run(ffmpeg(source, "-map", "0:a:0", "-vn", "-af", LOUDNORM, "-c:a", "aac", "-b:a", "128k", "-ar", "48000",
                "-map_metadata", "-1", audio.toString()), ffmpegLog, failed);
        run(ffmpeg(source, "-map", "0:a:0", "-vn", "-af", LOUDNORM, "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
                "-map_metadata", "-1", alarm.toString()), ffmpegLog, failed);
        String thumbAt = String.format(Locale.ROOT, "%.3f", Math.min(1.0, durationMs / 2000.0));
        run(List.of("ffmpeg", "-nostdin", "-y", "-v", "error", "-ss", thumbAt, "-i", source.toString(),
                "-frames:v", "1", "-vf", "scale=-2:480", "-q:v", "4", thumb.toString()), ffmpegLog, failed);
        return new Outputs(video, audio, alarm, thumb);
    }

    private static List<String> ffmpeg(Path source, String... outputArgs) {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-v", "error", "-i", source.toString()));
        command.addAll(List.of(outputArgs));
        return command;
    }

    /** Runs a command to completion, its output going to {@code output}; any failure is a TranscodeException. */
    private static void run(List<String> command, Path output, String reason) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        } catch (IOException e) {
            log.error("Could not start {}; is it installed and on the PATH?", command.getFirst(), e);
            throw new TranscodeException("Video processing isn't available right now. Try completing the upload again later.", e);
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TranscodeException(reason, new IOException(command.getFirst() + " timed out"));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TranscodeException(reason, e);
        }
        if (process.exitValue() != 0) {
            String details;
            try {
                details = Files.readString(output);
            } catch (IOException e) {
                details = "(no output)";
            }
            log.warn("{} exited with {}: {}", command.getFirst(), process.exitValue(), details.strip());
            throw new TranscodeException(reason, new IOException(command.getFirst() + " exited with " + process.exitValue()));
        }
    }

    public record Probe(long durationMs, boolean hasVideo, boolean hasAudio) {
    }

    public record Outputs(Path video, Path audio, Path alarm, Path thumb) {
    }

    /** A video that can't be processed. The message is for the uploader. */
    public static class TranscodeException extends RuntimeException {

        public TranscodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
