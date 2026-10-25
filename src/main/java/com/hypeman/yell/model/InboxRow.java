package com.hypeman.yell.model;

/** One inbox entry with the yell it delivers. */
public record InboxRow(YellDelivery delivery, Yell yell) {
}
