package com.ticketing.booking.model.vo;

import java.util.List;

/** Stable pagination envelope shared by list endpoints. */
public record PageVO<T>(List<T> items, int page, int size, boolean hasNext) {
    public PageVO {
        items = List.copyOf(items);
    }
}
