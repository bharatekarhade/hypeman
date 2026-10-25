package com.hypeman.yell.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param freeSendsPerMonth yells a user without Pro can send each calendar month (UTC); Pro is unlimited
 * @param maxAudioSize the largest audio file a yell can have: 10 seconds of AAC is far smaller
 * @param maxVideoSize the largest video file a yell can have
 */
@ConfigurationProperties("hypeman.yells")
public record YellProperties(int freeSendsPerMonth, DataSize maxAudioSize, DataSize maxVideoSize) {
}
