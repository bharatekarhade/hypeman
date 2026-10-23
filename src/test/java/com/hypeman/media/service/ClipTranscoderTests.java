package com.hypeman.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hypeman.media.service.ClipTranscoder.Outputs;
import com.hypeman.media.service.ClipTranscoder.Probe;
import com.hypeman.media.service.ClipTranscoder.TranscodeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real ffmpeg and ffprobe on clips ffmpeg generates itself: a test pattern with a tone. Skipped where ffmpeg isn't
 * installed (brew install ffmpeg); the production image has it.
 */
class ClipTranscoderTests {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ClipTranscoder transcoder = new ClipTranscoder(json);

    @TempDir
    Path dir;

    @BeforeAll
    static void needsFfmpeg() {
        assumeTrue(installed("ffmpeg") && installed("ffprobe"), "ffmpeg isn't installed");
    }

    @Test
    void probeFindsTheStreamsAndDuration() throws Exception {
        Path source = generate("portrait.mov", "720x1280", true, 3);

        Probe probe = transcoder.probe(source);

        assertThat(probe.hasVideo()).isTrue();
        assertThat(probe.hasAudio()).isTrue();
        assertThat(probe.durationMs()).isBetween(2_900L, 3_200L);
    }

    @Test
    void probeSeesAVideoWithNoSound() throws Exception {
        assertThat(transcoder.probe(generate("silent.mp4", "720x1280", false, 2)).hasAudio()).isFalse();
    }

    @Test
    void transcodingMakesTheFourFilesTheAppNeeds() throws Exception {
        Path source = generate("big.mov", "1440x2560", true, 3);

        Outputs outputs = transcoder.transcode(source, dir, 3_000);

        JsonNode video = streams(outputs.video());
        assertThat(codecs(video)).containsExactly("h264", "aac");
        assertThat(video.path("streams").get(0).path("height").asInt()).isEqualTo(1280);
        assertThat(video.path("streams").get(0).path("width").asInt()).isEqualTo(720);
        assertThat(codecs(streams(outputs.audio()))).containsExactly("aac");
        JsonNode alarm = streams(outputs.alarm());
        assertThat(codecs(alarm)).containsExactly("pcm_s16le");
        assertThat(alarm.path("format").path("format_name").asString()).isEqualTo("caf");
        assertThat(alarm.path("streams").get(0).path("channels").asInt()).isEqualTo(1);
        assertThat(codecs(streams(outputs.thumb()))).containsExactly("mjpeg");
        assertThat(streams(outputs.thumb()).path("streams").get(0).path("height").asInt()).isEqualTo(480);
    }

    @Test
    void aSmallVideoIsNotScaledUp() throws Exception {
        Outputs outputs = transcoder.transcode(generate("small.mp4", "360x640", true, 1), dir, 1_000);

        assertThat(streams(outputs.video()).path("streams").get(0).path("height").asInt()).isEqualTo(640);
    }

    @Test
    void aFileThatIsNotAVideoIsRejected() throws Exception {
        Path junk = Files.writeString(dir.resolve("junk.mp4"), "not a video");

        assertThatThrownBy(() -> transcoder.probe(junk))
                .isInstanceOf(TranscodeException.class)
                .hasMessage("We couldn't read that file. Upload an MP4 or MOV video.");
    }

    private Path generate(String name, String size, boolean withSound, int seconds) throws Exception {
        Path out = dir.resolve(name);
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "testsrc=size=" + size + ":rate=30:duration=" + seconds));
        if (withSound) {
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds));
        }
        command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", out.toString()));
        assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
        return out;
    }

    private JsonNode streams(Path file) throws Exception {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format",
                file.toString()).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        return json.readTree(output);
    }

    private static List<String> codecs(JsonNode probe) {
        List<String> codecs = new ArrayList<>();
        probe.path("streams").forEach(stream -> codecs.add(stream.path("codec_name").asString()));
        return codecs;
    }

    private static boolean installed(String command) {
        try {
            return new ProcessBuilder(command, "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
