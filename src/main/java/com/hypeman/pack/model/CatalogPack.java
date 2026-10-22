package com.hypeman.pack.model;

import java.util.List;

/** A pack with its clips in role order: a published one as the store shows it, or any pack for the admin. */
public record CatalogPack(Pack pack, List<Clip> clips) {
}
