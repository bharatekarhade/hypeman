package com.hypeman.yell.model;

public enum YellStatus {
    /** Created; the app is uploading its files. */
    UPLOADING,
    /** Sent; recipients can play it. */
    READY,
    /** Too long once checked, or taken down by moderation; nobody sees it. */
    REMOVED
}
