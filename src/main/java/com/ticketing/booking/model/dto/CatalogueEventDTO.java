package com.ticketing.booking.model.dto;

/** Validated event fields. A null field denotes an omitted patch property, never a JSON null. */
public record CatalogueEventDTO(String title, String description, String venueText) { }
