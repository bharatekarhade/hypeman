package com.hypeman.pack.model;

import java.util.List;

/** A published pack with its clips in role order, as the store shows it. */
public record CatalogPack(Pack pack, List<Clip> clips) {
}
